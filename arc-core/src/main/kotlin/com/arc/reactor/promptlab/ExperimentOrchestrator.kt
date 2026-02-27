package com.arc.reactor.promptlab

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.promptlab.analysis.FeedbackAnalyzer
import com.arc.reactor.promptlab.analysis.PromptCandidateGenerator
import com.arc.reactor.promptlab.eval.EvaluationPipeline
import com.arc.reactor.promptlab.eval.EvaluationPipelineFactory
import com.arc.reactor.promptlab.hook.ExperimentCaptureHook
import com.arc.reactor.promptlab.model.Experiment
import com.arc.reactor.promptlab.model.ExperimentStatus
import com.arc.reactor.promptlab.model.TestQuery
import com.arc.reactor.promptlab.model.TokenUsageSummary
import com.arc.reactor.promptlab.model.Trial
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Experiment execution engine.
 *
 * Runs prompt experiments by executing each version against test queries,
 * evaluating results through the 3-tier pipeline, and generating reports.
 */
class ExperimentOrchestrator(
    private val agentExecutor: AgentExecutor,
    private val promptTemplateStore: PromptTemplateStore,
    private val experimentStore: ExperimentStore,
    private val evaluationPipelineFactory: EvaluationPipelineFactory,
    private val reportGenerator: ReportGenerator,
    private val feedbackAnalyzer: FeedbackAnalyzer,
    private val candidateGenerator: PromptCandidateGenerator,
    private val properties: PromptLabProperties
) {

    /**
     * Execute an experiment: run all version x query x repetition trials.
     */
    suspend fun execute(experimentId: String): Experiment {
        val experiment = experimentStore.get(experimentId)
            ?: throw IllegalArgumentException("Experiment not found: $experimentId")
        require(experiment.status == ExperimentStatus.PENDING) {
            "Experiment must be PENDING, was: ${experiment.status}"
        }

        val running = experiment.copy(
            status = ExperimentStatus.RUNNING,
            startedAt = Instant.now()
        )
        experimentStore.save(running)
        logger.info { "Starting experiment: ${experiment.id} (${experiment.name})" }

        return try {
            withTimeout(properties.experimentTimeoutMs) {
                val trials = executeAllTrials(running)
                experimentStore.saveTrials(experimentId, trials)

                val report = reportGenerator.generate(running, trials)
                experimentStore.saveReport(experimentId, report)

                val completed = running.copy(
                    status = ExperimentStatus.COMPLETED,
                    completedAt = Instant.now()
                )
                experimentStore.save(completed)
                logger.info { "Experiment completed: $experimentId, trials=${trials.size}" }
                completed
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            val failed = running.copy(
                status = ExperimentStatus.FAILED,
                errorMessage = e.message,
                completedAt = Instant.now()
            )
            experimentStore.save(failed)
            logger.error { "Experiment failed: $experimentId - ${e.message}" }
            failed
        }
    }

    /**
     * Full automation pipeline: analyze feedback -> generate candidates -> run experiment.
     *
     * @return Completed experiment, or null if insufficient negative feedback
     */
    suspend fun runAutoPipeline(
        templateId: String,
        since: Instant? = null,
        candidateCount: Int? = null,
        judgeModel: String? = null
    ): Experiment? {
        logger.info { "Starting auto pipeline for template=$templateId" }

        val analysis = feedbackAnalyzer.analyze(templateId, since)
        if (analysis.negativeCount < properties.minNegativeFeedback) {
            logger.info {
                "Skipping: only ${analysis.negativeCount} negative feedback " +
                    "(min=${properties.minNegativeFeedback})"
            }
            return null
        }

        val candidateIds = candidateGenerator.generate(
            templateId = templateId,
            analysis = analysis,
            candidateCount = candidateCount ?: properties.candidateCount
        )
        if (candidateIds.isEmpty()) {
            logger.warn { "No candidates generated for template=$templateId" }
            return null
        }

        val activeVersion = promptTemplateStore.getActiveVersion(templateId)
            ?: throw IllegalStateException("No active version for template=$templateId")

        val experiment = Experiment(
            name = "Auto-optimize: $templateId",
            description = "Auto-generated experiment from ${analysis.negativeCount} negative feedback",
            templateId = templateId,
            baselineVersionId = activeVersion.id,
            candidateVersionIds = candidateIds,
            testQueries = analysis.sampleQueries,
            judgeModel = judgeModel,
            autoGenerated = true,
            createdAt = Instant.now()
        )
        experimentStore.save(experiment)
        return execute(experiment.id)
    }

    private suspend fun executeAllTrials(
        experiment: Experiment
    ): List<Trial> {
        val allVersionIds = listOf(experiment.baselineVersionId) +
            experiment.candidateVersionIds
        val pipeline = evaluationPipelineFactory.create(
            experiment.evaluationConfig
        )
        val trials = mutableListOf<Trial>()
        for (versionId in allVersionIds) {
            val version = promptTemplateStore.getVersion(versionId)
            if (version == null) {
                logger.warn { "Version not found, skipping: $versionId" }
                continue
            }
            executeVersionTrials(
                experiment, version, pipeline, trials
            )
        }
        return trials
    }

    private suspend fun executeVersionTrials(
        experiment: Experiment,
        version: com.arc.reactor.prompt.PromptVersion,
        pipeline: EvaluationPipeline,
        trials: MutableList<Trial>
    ) {
        for (query in experiment.testQueries) {
            for (rep in 0 until experiment.repetitions) {
                val trial = executeSingleTrial(
                    experiment, version.id, version.version,
                    version.content, query, rep, pipeline
                )
                trials.add(trial)
            }
        }
    }

    private suspend fun executeSingleTrial(
        experiment: Experiment,
        versionId: String,
        versionNumber: Int,
        systemPrompt: String,
        query: TestQuery,
        repetitionIndex: Int,
        pipeline: EvaluationPipeline
    ): Trial {
        val runId = UUID.randomUUID().toString()
        val command = AgentCommand(
            systemPrompt = systemPrompt,
            userPrompt = query.query,
            model = experiment.model,
            temperature = experiment.temperature,
            metadata = mapOf(
                ExperimentCaptureHook.EXPERIMENT_ID_KEY to experiment.id,
                ExperimentCaptureHook.VERSION_ID_KEY to versionId,
                ExperimentCaptureHook.RUN_ID_KEY to runId
            )
        )

        return try {
            val result = agentExecutor.execute(command)
            val evaluations = pipeline.evaluate(
                result.content.orEmpty(),
                query
            )
            buildTrial(
                experiment, versionId, versionNumber, query,
                repetitionIndex, result, evaluations
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "Trial failed for version=$versionId, query=${query.query}: ${e.message}" }
            Trial(
                experimentId = experiment.id,
                promptVersionId = versionId,
                promptVersionNumber = versionNumber,
                testQuery = query,
                repetitionIndex = repetitionIndex,
                success = false,
                errorMessage = e.message,
                executedAt = Instant.now()
            )
        }
    }

    private fun buildTrial(
        experiment: Experiment,
        versionId: String,
        versionNumber: Int,
        query: TestQuery,
        repetitionIndex: Int,
        result: AgentResult,
        evaluations: List<com.arc.reactor.promptlab.model.EvaluationResult>
    ): Trial {
        val tokenSummary = result.tokenUsage?.let {
            TokenUsageSummary(it.promptTokens, it.completionTokens, it.totalTokens)
        }
        return Trial(
            experimentId = experiment.id,
            promptVersionId = versionId,
            promptVersionNumber = versionNumber,
            testQuery = query,
            repetitionIndex = repetitionIndex,
            response = result.content,
            success = result.success,
            errorMessage = result.errorMessage,
            toolsUsed = result.toolsUsed,
            tokenUsage = tokenSummary,
            durationMs = result.durationMs,
            evaluations = evaluations,
            executedAt = Instant.now()
        )
    }
}
