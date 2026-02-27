package com.arc.reactor.controller

import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.promptlab.ExperimentOrchestrator
import com.arc.reactor.promptlab.ExperimentStore
import com.arc.reactor.promptlab.PromptLabProperties
import com.arc.reactor.promptlab.analysis.FeedbackAnalyzer
import com.arc.reactor.promptlab.model.EvaluationConfig
import com.arc.reactor.promptlab.model.Experiment
import com.arc.reactor.promptlab.model.ExperimentReport
import com.arc.reactor.promptlab.model.ExperimentStatus
import com.arc.reactor.promptlab.model.FeedbackAnalysis
import com.arc.reactor.promptlab.model.TestQuery
import com.arc.reactor.promptlab.model.TierStats
import com.arc.reactor.promptlab.model.Trial
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Prompt Lab REST API Controller
 *
 * Provides endpoints for managing prompt optimization experiments.
 * All endpoints require ADMIN role.
 */
@Tag(name = "Prompt Lab", description = "Prompt optimization experiments (ADMIN only)")
@RestController
@RequestMapping("/api/prompt-lab")
@ConditionalOnBean(ExperimentStore::class)
class PromptLabController(
    private val experimentStore: ExperimentStore,
    private val orchestrator: ExperimentOrchestrator,
    private val feedbackAnalyzer: FeedbackAnalyzer,
    private val promptTemplateStore: PromptTemplateStore,
    private val properties: PromptLabProperties
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        logger.error(t) { "Async experiment execution failed" }
    }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + exceptionHandler
    )
    private val runningJobs = ConcurrentHashMap<String, Job>()

    // ── Experiment CRUD ──

    @Operation(summary = "Create a new experiment (ADMIN)")
    @PostMapping("/experiments")
    fun createExperiment(
        @Valid @RequestBody request: CreateExperimentRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val validationError = validateExperimentLimits(request)
        if (validationError != null) return validationError

        val experiment = Experiment(
            name = request.name,
            description = request.description,
            templateId = request.templateId,
            baselineVersionId = request.baselineVersionId,
            candidateVersionIds = request.candidateVersionIds,
            testQueries = request.testQueries.map { it.toDomain() },
            evaluationConfig = request.evaluationConfig?.toDomain() ?: EvaluationConfig(),
            model = request.model,
            judgeModel = request.judgeModel,
            temperature = request.temperature ?: 0.3,
            repetitions = request.repetitions ?: 1,
            createdBy = currentActor(exchange),
            createdAt = Instant.now()
        )
        val saved = experimentStore.save(experiment)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    @Operation(summary = "List experiments with optional filters")
    @GetMapping("/experiments")
    fun listExperiments(
        @RequestParam(required = false) status: ExperimentStatus?,
        @RequestParam(required = false) templateId: String?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val experiments = experimentStore.list(status, templateId)
        return ResponseEntity.ok(experiments.map { it.toResponse() })
    }

    @Operation(summary = "Get experiment details")
    @GetMapping("/experiments/{id}")
    fun getExperiment(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val experiment = experimentStore.get(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(experiment.toResponse())
    }

    @Operation(summary = "Run experiment asynchronously (ADMIN)")
    @PostMapping("/experiments/{id}/run")
    fun runExperiment(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val experiment = experimentStore.get(id)
            ?: return ResponseEntity.notFound().build()
        if (experiment.status != ExperimentStatus.PENDING) {
            return ResponseEntity.badRequest().body(
                ErrorResponse(
                    error = "Experiment must be PENDING to run, current: ${experiment.status}",
                    timestamp = Instant.now().toString()
                )
            )
        }
        if (runningJobs.size >= properties.maxConcurrentExperiments) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                ErrorResponse(
                    error = "Max concurrent experiments reached: ${properties.maxConcurrentExperiments}",
                    timestamp = Instant.now().toString()
                )
            )
        }

        val job = scope.launch {
            try {
                orchestrator.execute(id)
            } finally {
                runningJobs.remove(id)
            }
        }
        runningJobs[id] = job
        return ResponseEntity.accepted().body(
            mapOf("status" to "RUNNING", "experimentId" to id)
        )
    }

    @Operation(summary = "Cancel a running experiment (ADMIN)")
    @PostMapping("/experiments/{id}/cancel")
    fun cancelExperiment(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val experiment = experimentStore.get(id)
            ?: return ResponseEntity.notFound().build()
        if (experiment.status != ExperimentStatus.RUNNING) {
            return ResponseEntity.badRequest().body(
                ErrorResponse(
                    error = "Only RUNNING experiments can be cancelled",
                    timestamp = Instant.now().toString()
                )
            )
        }
        runningJobs.remove(id)?.cancel()
        val cancelled = experiment.copy(
            status = ExperimentStatus.CANCELLED,
            completedAt = Instant.now()
        )
        experimentStore.save(cancelled)
        return ResponseEntity.ok(cancelled.toResponse())
    }

    @Operation(summary = "Get experiment execution status (ADMIN)")
    @GetMapping("/experiments/{id}/status")
    fun getStatus(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val experiment = experimentStore.get(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            ExperimentStatusResponse(
                experimentId = experiment.id,
                status = experiment.status.name,
                startedAt = experiment.startedAt?.toEpochMilli(),
                completedAt = experiment.completedAt?.toEpochMilli(),
                errorMessage = experiment.errorMessage
            )
        )
    }

    @Operation(summary = "Get experiment trial data (ADMIN)")
    @GetMapping("/experiments/{id}/trials")
    fun getTrials(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val trials = experimentStore.getTrials(id)
        return ResponseEntity.ok(trials.map { it.toResponse() })
    }

    @Operation(summary = "Get experiment report (ADMIN)")
    @GetMapping("/experiments/{id}/report")
    fun getReport(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val report = experimentStore.getReport(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(report.toResponse())
    }

    @Operation(summary = "Delete experiment (ADMIN)")
    @DeleteMapping("/experiments/{id}")
    fun deleteExperiment(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        experimentStore.delete(id)
        return ResponseEntity.noContent().build()
    }

    // ── Automation Endpoints ──

    @Operation(summary = "Run full auto-optimization pipeline (ADMIN)")
    @PostMapping("/auto-optimize")
    fun autoOptimize(
        @Valid @RequestBody request: AutoOptimizeRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val jobId = "auto-${request.templateId}-${System.currentTimeMillis()}"
        val job = scope.launch {
            try {
                orchestrator.runAutoPipeline(
                    templateId = request.templateId,
                    candidateCount = request.candidateCount,
                    judgeModel = request.judgeModel
                )
            } finally {
                runningJobs.remove(jobId)
            }
        }
        runningJobs[jobId] = job
        return ResponseEntity.accepted().body(
            mapOf("status" to "STARTED", "templateId" to request.templateId, "jobId" to jobId)
        )
    }

    @Operation(summary = "Analyze feedback for a template (ADMIN)")
    @PostMapping("/analyze")
    suspend fun analyzeFeedback(
        @Valid @RequestBody request: AnalyzeFeedbackRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val analysis = feedbackAnalyzer.analyze(
            templateId = request.templateId,
            maxSamples = request.maxSamples ?: 50
        )
        return ResponseEntity.ok(analysis.toResponse())
    }

    @Operation(summary = "Activate recommended version from experiment (ADMIN/HITL)")
    @PostMapping("/experiments/{id}/activate")
    fun activateRecommended(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val experiment = experimentStore.get(id)
            ?: return ResponseEntity.notFound().build()
        val report = experimentStore.getReport(id)
            ?: return ResponseEntity.badRequest().body(
                ErrorResponse(
                    error = "No report available for this experiment",
                    timestamp = Instant.now().toString()
                )
            )

        val activated = promptTemplateStore.activateVersion(
            experiment.templateId,
            report.recommendation.bestVersionId
        ) ?: return ResponseEntity.badRequest().body(
            ErrorResponse(
                error = "Failed to activate version: ${report.recommendation.bestVersionId}",
                timestamp = Instant.now().toString()
            )
        )

        return ResponseEntity.ok(
            mapOf(
                "activated" to true,
                "templateId" to experiment.templateId,
                "versionId" to activated.id,
                "versionNumber" to activated.version
            )
        )
    }

    // ── Validation ──

    private fun validateExperimentLimits(
        request: CreateExperimentRequest
    ): ResponseEntity<Any>? {
        val maxQ = properties.maxQueriesPerExperiment
        if (request.testQueries.size > maxQ) {
            return badRequest("testQueries exceeds limit: ${request.testQueries.size} > $maxQ")
        }
        val maxV = properties.maxVersionsPerExperiment
        val totalVersions = 1 + request.candidateVersionIds.size
        if (totalVersions > maxV) {
            return badRequest("Total versions exceeds limit: $totalVersions > $maxV")
        }
        val maxR = properties.maxRepetitions
        val reps = request.repetitions ?: 1
        if (reps > maxR) {
            return badRequest("repetitions exceeds limit: $reps > $maxR")
        }
        return null
    }

    private fun badRequest(msg: String): ResponseEntity<Any> {
        return ResponseEntity.badRequest().body(
            ErrorResponse(error = msg, timestamp = Instant.now().toString())
        )
    }
}

// ── Request DTOs ──

data class CreateExperimentRequest(
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    val description: String = "",
    @field:NotBlank(message = "templateId must not be blank")
    val templateId: String,
    @field:NotBlank(message = "baselineVersionId must not be blank")
    val baselineVersionId: String,
    @field:NotEmpty(message = "candidateVersionIds must not be empty")
    val candidateVersionIds: List<String>,
    @field:NotEmpty(message = "testQueries must not be empty")
    val testQueries: List<TestQueryRequest>,
    val evaluationConfig: EvaluationConfigRequest? = null,
    val model: String? = null,
    val judgeModel: String? = null,
    val temperature: Double? = null,
    val repetitions: Int? = null
)

data class TestQueryRequest(
    @field:NotBlank(message = "query must not be blank")
    val query: String,
    val intent: String? = null,
    val domain: String? = null,
    val expectedBehavior: String? = null,
    val tags: List<String> = emptyList()
) {
    fun toDomain() = TestQuery(query, intent, domain, expectedBehavior, tags)
}

data class EvaluationConfigRequest(
    val structuralEnabled: Boolean = true,
    val rulesEnabled: Boolean = true,
    val llmJudgeEnabled: Boolean = true,
    val llmJudgeBudgetTokens: Int = 100_000,
    val customRubric: String? = null
) {
    fun toDomain() = EvaluationConfig(
        structuralEnabled, rulesEnabled, llmJudgeEnabled,
        llmJudgeBudgetTokens, customRubric
    )
}

data class AutoOptimizeRequest(
    @field:NotBlank(message = "templateId must not be blank")
    val templateId: String,
    val candidateCount: Int? = null,
    val judgeModel: String? = null
)

data class AnalyzeFeedbackRequest(
    @field:NotBlank(message = "templateId must not be blank")
    val templateId: String,
    val maxSamples: Int? = null
)

// ── Response DTOs ──

data class ExperimentResponse(
    val id: String,
    val name: String,
    val description: String,
    val templateId: String,
    val baselineVersionId: String,
    val candidateVersionIds: List<String>,
    val status: String,
    val autoGenerated: Boolean,
    val createdBy: String,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?
)

data class ExperimentStatusResponse(
    val experimentId: String,
    val status: String,
    val startedAt: Long?,
    val completedAt: Long?,
    val errorMessage: String?
)

data class TrialResponse(
    val id: String,
    val promptVersionId: String,
    val promptVersionNumber: Int,
    val query: String,
    val response: String?,
    val success: Boolean,
    val score: Double,
    val durationMs: Long,
    val toolsUsed: List<String>,
    val passed: Boolean,
    val executedAt: Long
)

data class FeedbackAnalysisResponse(
    val totalFeedback: Int,
    val negativeCount: Int,
    val weaknesses: List<WeaknessResponse>,
    val sampleQueryCount: Int,
    val analyzedAt: Long
)

data class WeaknessResponse(
    val category: String,
    val description: String,
    val frequency: Int,
    val exampleQueries: List<String>
)

data class ReportResponse(
    val experimentId: String,
    val experimentName: String,
    val generatedAt: Long,
    val totalTrials: Int,
    val versionSummaries: List<VersionSummaryResponse>,
    val recommendation: RecommendationResponse
)

data class VersionSummaryResponse(
    val versionId: String,
    val versionNumber: Int,
    val isBaseline: Boolean,
    val totalTrials: Int,
    val passCount: Int,
    val passRate: Double,
    val avgScore: Double,
    val avgDurationMs: Double,
    val totalTokens: Int,
    val errorRate: Double,
    val tierBreakdown: Map<String, TierStats>,
    val toolUsageFrequency: Map<String, Int>
)

data class RecommendationResponse(
    val bestVersionId: String,
    val bestVersionNumber: Int,
    val confidence: String,
    val reasoning: String,
    val improvements: List<String>,
    val warnings: List<String>
)

// ── Mapping Extensions ──

private fun Experiment.toResponse() = ExperimentResponse(
    id = id,
    name = name,
    description = description,
    templateId = templateId,
    baselineVersionId = baselineVersionId,
    candidateVersionIds = candidateVersionIds,
    status = status.name,
    autoGenerated = autoGenerated,
    createdBy = createdBy,
    createdAt = createdAt.toEpochMilli(),
    startedAt = startedAt?.toEpochMilli(),
    completedAt = completedAt?.toEpochMilli()
)

private fun Trial.toResponse() = TrialResponse(
    id = id,
    promptVersionId = promptVersionId,
    promptVersionNumber = promptVersionNumber,
    query = testQuery.query,
    response = response,
    success = success,
    score = evaluations.map { it.score }.average().let { if (it.isNaN()) 0.0 else it },
    durationMs = durationMs,
    toolsUsed = toolsUsed,
    passed = evaluations.all { it.passed },
    executedAt = executedAt.toEpochMilli()
)

private fun ExperimentReport.toResponse() = ReportResponse(
    experimentId = experimentId,
    experimentName = experimentName,
    generatedAt = generatedAt.toEpochMilli(),
    totalTrials = totalTrials,
    versionSummaries = versionSummaries.map { v ->
        VersionSummaryResponse(
            versionId = v.versionId,
            versionNumber = v.versionNumber,
            isBaseline = v.isBaseline,
            totalTrials = v.totalTrials,
            passCount = v.passCount,
            passRate = v.passRate,
            avgScore = v.avgScore,
            avgDurationMs = v.avgDurationMs,
            totalTokens = v.totalTokens,
            errorRate = v.errorRate,
            tierBreakdown = v.tierBreakdown.mapKeys { it.key.name },
            toolUsageFrequency = v.toolUsageFrequency
        )
    },
    recommendation = RecommendationResponse(
        bestVersionId = recommendation.bestVersionId,
        bestVersionNumber = recommendation.bestVersionNumber,
        confidence = recommendation.confidence.name,
        reasoning = recommendation.reasoning,
        improvements = recommendation.improvements,
        warnings = recommendation.warnings
    )
)

private fun FeedbackAnalysis.toResponse() = FeedbackAnalysisResponse(
    totalFeedback = totalFeedback,
    negativeCount = negativeCount,
    weaknesses = weaknesses.map {
        WeaknessResponse(it.category, it.description, it.frequency, it.exampleQueries)
    },
    sampleQueryCount = sampleQueries.size,
    analyzedAt = analyzedAt.toEpochMilli()
)
