package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.scheduler.DynamicSchedulerService
import mu.KotlinLogging
import com.arc.reactor.scheduler.ScheduledJob
import com.arc.reactor.scheduler.ScheduledJobExecution
import com.arc.reactor.scheduler.ScheduledJobType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.TimeoutException
/**
 * 동적 스케줄러 API 컨트롤러.
 *
 * 예약된 작업을 관리하는 REST API를 제공합니다. 모든 작업은 ADMIN 권한이 필요합니다.
 *
 * ## 작업 유형
 * - **MCP_TOOL** (기본값): 스케줄에 따라 단일 MCP 도구를 호출한다.
 * - **AGENT**: 전체 ReAct 에이전트 루프를 실행하고 자연어 결과를 생성한다.
 *
 * ## 엔드포인트
 * - GET    /api/scheduler/jobs                   : 전체 예약 작업 목록 조회
 * - POST   /api/scheduler/jobs                   : 새 예약 작업 생성
 * - GET    /api/scheduler/jobs/{id}              : 작업 상세 조회
 * - PUT    /api/scheduler/jobs/{id}              : 작업 수정
 * - DELETE /api/scheduler/jobs/{id}              : 작업 삭제
 * - POST   /api/scheduler/jobs/{id}/trigger      : 즉시 실행 트리거
 * - POST   /api/scheduler/jobs/{id}/dry-run      : 드라이런 (부작용 없는 실행)
 * - GET    /api/scheduler/jobs/{id}/executions   : 실행 이력 조회
 *
 * @see DynamicSchedulerService
 */
private val logger = KotlinLogging.logger {}

@Tag(name = "Scheduler", description = "Dynamic scheduled job execution (ADMIN only)")
@RestController
@RequestMapping("/api/scheduler/jobs")
@ConditionalOnProperty(prefix = "arc.reactor.scheduler", name = ["enabled"], havingValue = "true")
class SchedulerController(
    private val schedulerService: DynamicSchedulerService,
    private val agentProperties: AgentProperties = AgentProperties()
) {

    /**
     * R297: Mono 기반 trigger/dryRun 작업의 timeout. SchedulerProperties의 기본 실행
     * timeout과 동일 값(기본 5분)을 사용하여 hang하는 작업이 boundedElastic 풀을 고갈
     * 시키지 못하도록 보장한다.
     */
    private val executionTimeout: Duration =
        Duration.ofMillis(agentProperties.scheduler.defaultExecutionTimeoutMs.coerceAtLeast(1000))

    /** 전체 예약 작업 목록을 조회한다. 선택적으로 태그로 필터링한다. */
    @Operation(summary = "전체 예약 작업 목록 조회 (태그 필터 선택)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Paginated list of scheduled jobs"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun listJobs(
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) tag: String? = null,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val clamped = clampLimit(limit)
        val jobs = schedulerService.list()
        val filtered = if (tag.isNullOrBlank()) jobs else jobs.filter { tag in it.tags }
        return ResponseEntity.ok(filtered.map { it.toResponse() }.paginate(offset, clamped))
    }

    /** 새 예약 작업을 생성한다. MCP_TOOL이면 서버명/도구명 필수, AGENT면 프롬프트 필수. */
    @Operation(summary = "새 예약 작업 생성 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Scheduled job created"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping
    fun createJob(
        @Valid @RequestBody request: CreateScheduledJobRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val job = try {
            schedulerService.create(request.toScheduledJob())
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "잘못된 작업 생성 요청" }
            return badRequestResponse("Invalid request")
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(job.toResponse())
    }

    /** 예약 작업 상세 정보를 조회한다. */
    @Operation(summary = "예약 작업 상세 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Scheduled job details"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Scheduled job not found")
    ])
    @GetMapping("/{id}")
    fun getJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val job = schedulerService.findById(id) ?: return notFoundResponse("Scheduled job not found: $id")
        return ResponseEntity.ok(job.toResponse())
    }

    /** 예약 작업을 수정한다. */
    @Operation(summary = "예약 작업 수정 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Scheduled job updated"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Scheduled job not found")
    ])
    @PutMapping("/{id}")
    fun updateJob(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateScheduledJobRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val updated = try {
            schedulerService.update(id, request.toScheduledJob()) ?: return notFoundResponse("Scheduled job not found: $id")
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "잘못된 작업 수정 요청: $id" }
            return badRequestResponse("Invalid request")
        }
        return ResponseEntity.ok(updated.toResponse())
    }

    /** 예약 작업을 삭제한다. */
    @Operation(summary = "예약 작업 삭제 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Scheduled job deleted"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Scheduled job not found")
    ])
    @DeleteMapping("/{id}")
    fun deleteJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        schedulerService.findById(id) ?: return notFoundResponse("Scheduled job not found: $id")
        schedulerService.delete(id)
        return ResponseEntity.noContent().build()
    }

    /**
     * 예약 작업을 즉시 실행한다.
     *
     * R297 fix: `.timeout(executionTimeout)` 추가. 이전 구현은 hang하는 작업이
     * boundedElastic 워커 스레드를 무한히 점유할 수 있어 풀 고갈 → 다른 trigger/dryRun
     * 요청 처리 불가. R297에서는 SchedulerProperties.defaultExecutionTimeoutMs (기본 5분)
     * 초과 시 [TimeoutException] → 504 Gateway Timeout 반환.
     */
    @Operation(summary = "예약 작업 즉시 실행 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Job triggered"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Scheduled job not found"),
        ApiResponse(responseCode = "504", description = "Job execution timed out")
    ])
    @PostMapping("/{id}/trigger")
    fun triggerJob(@PathVariable id: String, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
        if (!isAdmin(exchange)) return Mono.just(forbiddenResponse())
        schedulerService.findById(id) ?: return Mono.just(notFoundResponse("Scheduled job not found: $id"))
        return Mono.fromCallable {
            val result = schedulerService.trigger(id)
            ResponseEntity.ok<Any>(mapOf("result" to result))
        }
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(executionTimeout) // R297: hang 방지
            .onErrorResume(TimeoutException::class.java) { _ ->
                logger.warn { "예약 작업 trigger timeout: id=$id, timeout=${executionTimeout.toSeconds()}s" }
                Mono.just(timeoutResponse(id))
            }
    }

    /**
     * 상태 기록이나 알림 전송 없이 예약 작업을 드라이런한다.
     *
     * R297 fix: triggerJob과 동일한 timeout 적용.
     */
    @Operation(summary = "예약 작업 드라이런 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Dry-run result"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Scheduled job not found"),
        ApiResponse(responseCode = "504", description = "Dry-run execution timed out")
    ])
    @PostMapping("/{id}/dry-run")
    fun dryRunJob(@PathVariable id: String, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
        if (!isAdmin(exchange)) return Mono.just(forbiddenResponse())
        schedulerService.findById(id) ?: return Mono.just(notFoundResponse("Scheduled job not found: $id"))
        return Mono.fromCallable {
            val result = schedulerService.dryRun(id)
            ResponseEntity.ok<Any>(mapOf("result" to result, "dryRun" to true))
        }
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(executionTimeout) // R297: hang 방지
            .onErrorResume(TimeoutException::class.java) { _ ->
                logger.warn { "예약 작업 dryRun timeout: id=$id, timeout=${executionTimeout.toSeconds()}s" }
                Mono.just(timeoutResponse(id))
            }
    }

    /** R297: 504 Gateway Timeout 응답. e.message는 노출하지 않고 일반 안내만. */
    private fun timeoutResponse(jobId: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(
            ErrorResponse(
                error = "Job execution timed out after ${executionTimeout.toSeconds()}s. " +
                    "작업이 지정된 timeout 내에 완료되지 않았습니다. (jobId=$jobId)",
                timestamp = java.time.Instant.now().toString()
            )
        )
    }

    /** 예약 작업의 실행 이력을 조회한다. */
    @Operation(summary = "예약 작업 실행 이력 조회 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Paginated execution history"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Scheduled job not found")
    ])
    @GetMapping("/{id}/executions")
    fun getExecutions(
        @PathVariable id: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") pageLimit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        schedulerService.findById(id) ?: return notFoundResponse("Scheduled job not found: $id")
        val executions = schedulerService.getExecutions(id, limit.coerceIn(1, 100))
        val clamped = clampLimit(pageLimit)
        return ResponseEntity.ok(executions.map { it.toResponse() }.paginate(offset, clamped))
    }

    private fun ScheduledJob.toResponse() = ScheduledJobResponse(
        id = id,
        name = name,
        description = description,
        cronExpression = cronExpression,
        timezone = timezone,
        jobType = jobType.name,
        mcpServerName = mcpServerName,
        toolName = toolName,
        toolArguments = toolArguments,
        agentPrompt = agentPrompt,
        personaId = personaId,
        agentSystemPrompt = agentSystemPrompt,
        agentModel = agentModel,
        agentMaxToolCalls = agentMaxToolCalls,
        tags = tags,
        slackChannelId = slackChannelId,
        teamsWebhookUrl = teamsWebhookUrl,
        retryOnFailure = retryOnFailure,
        maxRetryCount = maxRetryCount,
        executionTimeoutMs = executionTimeoutMs,
        enabled = enabled,
        lastRunAt = lastRunAt?.toEpochMilli(),
        lastStatus = lastStatus?.name,
        lastResult = lastResult,
        lastResultPreview = schedulerResultPreview(lastResult),
        lastFailureReason = schedulerFailureReason(lastResult),
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli()
    )

    private fun ScheduledJobExecution.toResponse() = ScheduledJobExecutionResponse(
        id = id,
        jobId = jobId,
        jobName = jobName,
        status = status.name,
        result = result,
        resultPreview = schedulerResultPreview(result),
        failureReason = schedulerFailureReason(result),
        durationMs = durationMs,
        dryRun = dryRun,
        startedAt = startedAt.toEpochMilli(),
        completedAt = completedAt?.toEpochMilli()
    )
}

data class CreateScheduledJobRequest(
    @field:NotBlank(message = "Job name is required")
    @field:Size(max = 200, message = "Job name must not exceed 200 characters")
    val name: String,
    val description: String? = null,
    @field:NotBlank(message = "Cron expression is required")
    val cronExpression: String,
    val timezone: String = "Asia/Seoul",

    /** MCP_TOOL (기본값) 또는 AGENT */
    val jobType: String = "MCP_TOOL",

    // MCP_TOOL 모드
    val mcpServerName: String? = null,
    val toolName: String? = null,
    val toolArguments: Map<String, Any> = emptyMap(),

    // AGENT 모드
    val agentPrompt: String? = null,
    val personaId: String? = null,
    val agentSystemPrompt: String? = null,
    val agentModel: String? = null,
    val agentMaxToolCalls: Int? = null,

    val tags: Set<String> = emptySet(),
    val slackChannelId: String? = null,
    val teamsWebhookUrl: String? = null,
    val retryOnFailure: Boolean = false,
    val maxRetryCount: Int = 3,
    val executionTimeoutMs: Long? = null,
    val enabled: Boolean = true
) {
    fun toScheduledJob(): ScheduledJob {
        val type = parseJobType(jobType)
        validate(type)
        return ScheduledJob(
            name = name,
            description = description,
            cronExpression = cronExpression,
            timezone = timezone,
            jobType = type,
            mcpServerName = mcpServerName,
            toolName = toolName,
            toolArguments = toolArguments,
            agentPrompt = agentPrompt,
            personaId = personaId,
            agentSystemPrompt = agentSystemPrompt,
            agentModel = agentModel,
            agentMaxToolCalls = agentMaxToolCalls,
            tags = tags,
            slackChannelId = slackChannelId,
            teamsWebhookUrl = teamsWebhookUrl,
            retryOnFailure = retryOnFailure,
            maxRetryCount = maxRetryCount,
            executionTimeoutMs = executionTimeoutMs,
            enabled = enabled
        )
    }

    private fun validate(type: ScheduledJobType) =
        validateJobFields(type, mcpServerName, toolName, agentPrompt)
}

data class UpdateScheduledJobRequest(
    @field:NotBlank(message = "Job name is required")
    val name: String,
    val description: String? = null,
    @field:NotBlank(message = "Cron expression is required")
    val cronExpression: String,
    val timezone: String = "Asia/Seoul",

    val jobType: String = "MCP_TOOL",

    // MCP_TOOL 모드
    val mcpServerName: String? = null,
    val toolName: String? = null,
    val toolArguments: Map<String, Any> = emptyMap(),

    // AGENT 모드
    val agentPrompt: String? = null,
    val personaId: String? = null,
    val agentSystemPrompt: String? = null,
    val agentModel: String? = null,
    val agentMaxToolCalls: Int? = null,

    val tags: Set<String> = emptySet(),
    val slackChannelId: String? = null,
    val teamsWebhookUrl: String? = null,
    val retryOnFailure: Boolean = false,
    val maxRetryCount: Int = 3,
    val executionTimeoutMs: Long? = null,
    val enabled: Boolean = true
) {
    fun toScheduledJob(): ScheduledJob {
        val type = parseJobType(jobType)
        validateJobFields(type, mcpServerName, toolName, agentPrompt)
        return ScheduledJob(
            name = name,
            description = description,
            cronExpression = cronExpression,
            timezone = timezone,
            jobType = type,
            mcpServerName = mcpServerName,
            toolName = toolName,
            toolArguments = toolArguments,
            agentPrompt = agentPrompt,
            personaId = personaId,
            agentSystemPrompt = agentSystemPrompt,
            agentModel = agentModel,
            agentMaxToolCalls = agentMaxToolCalls,
            tags = tags,
            slackChannelId = slackChannelId,
            teamsWebhookUrl = teamsWebhookUrl,
            retryOnFailure = retryOnFailure,
            maxRetryCount = maxRetryCount,
            executionTimeoutMs = executionTimeoutMs,
            enabled = enabled
        )
    }
}

data class ScheduledJobResponse(
    val id: String,
    val name: String,
    val description: String?,
    val cronExpression: String,
    val timezone: String,
    val jobType: String,
    val mcpServerName: String?,
    val toolName: String?,
    val toolArguments: Map<String, Any>,
    val agentPrompt: String?,
    val personaId: String?,
    val agentSystemPrompt: String?,
    val agentModel: String?,
    val agentMaxToolCalls: Int?,
    val tags: Set<String>,
    val slackChannelId: String?,
    val teamsWebhookUrl: String?,
    val retryOnFailure: Boolean,
    val maxRetryCount: Int,
    val executionTimeoutMs: Long?,
    val enabled: Boolean,
    val lastRunAt: Long?,
    val lastStatus: String?,
    val lastResult: String?,
    val lastResultPreview: String?,
    val lastFailureReason: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class ScheduledJobExecutionResponse(
    val id: String,
    val jobId: String,
    val jobName: String,
    val status: String,
    val result: String?,
    val resultPreview: String?,
    val failureReason: String?,
    val durationMs: Long,
    val dryRun: Boolean,
    val startedAt: Long,
    val completedAt: Long?
)

/** 작업 유형별 필수 필드를 검증한다. Create/Update DTO에서 공통으로 사용. */
private fun validateJobFields(
    type: ScheduledJobType,
    mcpServerName: String?,
    toolName: String?,
    agentPrompt: String?
) {
    when (type) {
        ScheduledJobType.MCP_TOOL -> {
            require(!mcpServerName.isNullOrBlank()) { "MCP_TOOL 작업에는 mcpServerName이 필수이다" }
            require(!toolName.isNullOrBlank()) { "MCP_TOOL 작업에는 toolName이 필수이다" }
        }
        ScheduledJobType.AGENT -> {
            require(!agentPrompt.isNullOrBlank()) { "AGENT 작업에는 agentPrompt가 필수이다" }
        }
    }
}

private fun parseJobType(value: String): ScheduledJobType =
    try {
        ScheduledJobType.valueOf(value.uppercase())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid jobType '$value'. Must be one of: ${ScheduledJobType.entries.joinToString()}")
    }
