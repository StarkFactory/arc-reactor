package com.arc.reactor.scheduler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.SchedulerProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.persona.resolveEffectivePrompt
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronExpression
import org.springframework.scheduling.support.CronTrigger
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

private val logger = KotlinLogging.logger {}

/**
 * 동적 스케줄러 서비스
 *
 * 런타임에 크론 스케줄 작업 실행을 관리한다.
 * 작업은 REST API를 통해 생성, 갱신, 삭제, 수동 트리거할 수 있다.
 *
 * 두 가지 실행 모드를 지원한다:
 * - **MCP_TOOL**: 단일 MCP 도구를 직접 호출한다 (원래 동작).
 * - **AGENT**: 전체 ReAct 에이전트 루프를 실행한다. LLM이 여러 MCP 도구를 추론하여
 *   자연어 요약을 생성한다.
 *
 * ## 크론 스케줄링 흐름
 * 1. 시작 시: 스토어에서 활성화된 모든 작업을 로딩하고 크론 트리거를 등록한다
 * 2. 작업 생성/갱신: 기존 트리거를 취소하고 새 트리거를 등록한다
 * 3. 크론 트리거 발동: executeJob() -> runJobWithRetryAndTimeout() -> executeJobContent()
 * 4. executeJobContent()에서 jobType에 따라 MCP_TOOL 또는 AGENT 모드로 분기
 * 5. 실행 결과를 Slack/Teams로 전송하고, 실행 이력을 기록한다
 *
 * ## 시스템 프롬프트 결정 순서 (AGENT 모드)
 * agentSystemPrompt -> personaId의 페르소나 -> 기본 페르소나 -> DEFAULT_SYSTEM_PROMPT
 *
 * WHY: 반복적 작업(일일 브리핑, 정기 보고서 등)을 자동화하여
 * 사용자가 수동으로 매번 요청할 필요 없게 한다.
 * 두 모드를 지원하여 단순 도구 호출과 복잡한 추론 작업을 모두 스케줄링한다.
 *
 * @see ScheduledJob 작업 정의 모델
 * @see ScheduledJobStore 작업 저장소
 * @see ScheduledJobExecutionStore 실행 이력 저장소
 */
class DynamicSchedulerService(
    private val store: ScheduledJobStore,
    private val taskScheduler: TaskScheduler,
    private val mcpManager: McpManager,
    private val slackMessageSender: SlackMessageSender? = null,
    private val teamsMessageSender: TeamsMessageSender? = null,
    private val hookExecutor: HookExecutor? = null,
    private val toolApprovalPolicy: ToolApprovalPolicy? = null,
    private val pendingApprovalStore: PendingApprovalStore? = null,
    private val agentExecutor: AgentExecutor? = null,
    private val agentExecutorProvider: (() -> AgentExecutor?)? = null,
    private val personaStore: PersonaStore? = null,
    private val promptTemplateStore: PromptTemplateStore? = null,
    private val executionStore: ScheduledJobExecutionStore? = null,
    private val schedulerProperties: SchedulerProperties = SchedulerProperties()
) : DisposableBean {

    companion object {
        private const val SCHEDULER_ACTOR = "scheduler"
        private const val SCHEDULER_CHANNEL = "scheduler"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful AI assistant powered by Arc Reactor. " +
                "Be concise and direct. Use available tools to provide accurate answers. " +
                "Do not fabricate citations or sources."
        private const val RETRY_DELAY_MS = 2000L
        private const val MIN_EXECUTION_TIMEOUT_MS = 1000L
        private const val MAX_EXECUTION_TIMEOUT_MS = 3_600_000L
        private const val MESSAGE_TRUNCATION_LIMIT = 3000
        private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    private val scheduledFutures = ConcurrentHashMap<String, ScheduledFuture<*>>()

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        val jobs = store.list().filter { it.enabled }
        logger.info { "Dynamic Scheduler: loading ${jobs.size} enabled jobs" }
        for (job in jobs) {
            registerJob(job)
        }
    }

    override fun destroy() {
        val count = scheduledFutures.size
        logger.info { "Dynamic Scheduler: cancelling $count scheduled jobs" }
        scheduledFutures.values.forEach { it.cancel(false) }
        scheduledFutures.clear()
    }

    fun create(job: ScheduledJob): ScheduledJob {
        validateSchedule(job)
        val saved = store.save(job)
        if (saved.enabled) registerJob(saved)
        logger.info { "Scheduled job created: ${saved.name} (${saved.cronExpression})" }
        return saved
    }

    fun update(id: String, job: ScheduledJob): ScheduledJob? {
        validateSchedule(job)
        val updated = store.update(id, job) ?: return null
        cancelJob(id)
        if (updated.enabled) registerJob(updated)
        logger.info { "Scheduled job updated: ${updated.name} (${updated.cronExpression})" }
        return updated
    }

    fun delete(id: String) {
        cancelJob(id)
        store.delete(id)
        logger.info { "Scheduled job deleted: $id" }
    }

    fun trigger(id: String): String {
        val job = store.findById(id) ?: return "Job not found: $id"
        return executeJob(job)
    }

    fun dryRun(id: String): String {
        val job = store.findById(id) ?: return "Job not found: $id"
        return executeDryRun(job)
    }

    fun getExecutions(jobId: String, limit: Int): List<ScheduledJobExecution> =
        executionStore?.findByJobId(jobId, limit) ?: emptyList()

    fun list(): List<ScheduledJob> = store.list()

    fun findById(id: String): ScheduledJob? = store.findById(id)

    fun findByName(name: String): ScheduledJob? = store.findByName(name)

    private fun registerJob(job: ScheduledJob) {
        try {
            val zone = ZoneId.of(job.timezone)
            val trigger = CronTrigger(job.cronExpression, zone)
            val future = taskScheduler.schedule({ executeJob(job) }, trigger)
            if (future != null) {
                scheduledFutures[job.id] = future
                val target = when (job.jobType) {
                    ScheduledJobType.MCP_TOOL -> "-> ${job.mcpServerName}/${job.toolName}"
                    ScheduledJobType.AGENT -> "-> agent(personaId=${job.personaId})"
                }
                logger.info { "Registered cron job: ${job.name} [${job.cronExpression}] $target" }
            } else {
                val message = "Failed to register cron job '${job.name}': scheduler returned null future"
                logger.warn { message }
                markSchedulingFailure(job, message)
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Failed to register cron job: ${job.name}" }
            markSchedulingFailure(job, "Failed to register cron job: ${e.message}")
        }
    }

    private fun validateSchedule(job: ScheduledJob) {
        validateTimezone(job.timezone)
        validateCronExpression(job.cronExpression)
        validateJobName(job.name)
        validateExecutionTimeout(job.executionTimeoutMs)
        validateRetryConfig(job)
        validateJobTypeFields(job)
    }

    private fun validateTimezone(timezone: String) {
        try {
            ZoneId.of(timezone)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid timezone: $timezone", e)
        }
    }

    private fun validateCronExpression(cron: String) {
        try {
            CronExpression.parse(cron)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid cron expression: $cron", e)
        }
    }

    private fun validateJobName(name: String) {
        require(name.isNotBlank()) { "Job name must not be blank" }
    }

    private fun validateExecutionTimeout(timeoutMs: Long?) {
        if (timeoutMs == null || timeoutMs == 0L) return
        require(timeoutMs in MIN_EXECUTION_TIMEOUT_MS..MAX_EXECUTION_TIMEOUT_MS) {
            "executionTimeoutMs must be 0 (unlimited) or between $MIN_EXECUTION_TIMEOUT_MS and $MAX_EXECUTION_TIMEOUT_MS, got: $timeoutMs"
        }
    }

    private fun validateRetryConfig(job: ScheduledJob) {
        if (!job.retryOnFailure) return
        require(job.maxRetryCount >= 1) {
            "maxRetryCount must be >= 1 when retryOnFailure is enabled, got: ${job.maxRetryCount}"
        }
    }

    private fun validateJobTypeFields(job: ScheduledJob) {
        when (job.jobType) {
            ScheduledJobType.MCP_TOOL -> {
                require(!job.mcpServerName.isNullOrBlank()) {
                    "mcpServerName is required for MCP_TOOL job"
                }
                require(!job.toolName.isNullOrBlank()) {
                    "toolName is required for MCP_TOOL job"
                }
            }
            ScheduledJobType.AGENT -> {
                require(!job.agentPrompt.isNullOrBlank()) {
                    "agentPrompt is required for AGENT job"
                }
            }
        }
    }

    private fun cancelJob(id: String) {
        scheduledFutures.remove(id)?.cancel(false)
    }

    private fun executeJob(job: ScheduledJob): String {
        logger.info { "Executing scheduled job: ${job.name} [${job.jobType}]" }
        store.updateExecutionResult(job.id, JobExecutionStatus.RUNNING, null)

        val startedAt = Instant.now()
        return try {
            val result = runJobWithRetryAndTimeout(job)

            sendSlackIfConfigured(job, result)
            sendTeamsIfConfigured(job, result)
            store.updateExecutionResult(job.id, JobExecutionStatus.SUCCESS, result)
            val durationMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
            recordExecution(job, JobExecutionStatus.SUCCESS, result, durationMs, false, startedAt)
            logger.info { "Scheduled job completed: ${job.name}" }
            result
        } catch (e: Exception) {
            e.throwIfCancellation()
            val errorMsg = "Job '${job.name}' failed: ${e.message}"
            logger.error(e) { errorMsg }
            store.updateExecutionResult(job.id, JobExecutionStatus.FAILED, errorMsg)
            val durationMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
            recordExecution(job, JobExecutionStatus.FAILED, errorMsg, durationMs, false, startedAt)
            errorMsg
        }
    }

    private fun executeDryRun(job: ScheduledJob): String {
        logger.info { "Dry-run scheduled job: ${job.name} [${job.jobType}]" }
        val startedAt = Instant.now()
        return try {
            val result = runJobWithRetryAndTimeout(job)
            val durationMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
            recordExecution(job, JobExecutionStatus.SUCCESS, result, durationMs, true, startedAt)
            logger.info { "Dry-run completed: ${job.name}" }
            result
        } catch (e: Exception) {
            e.throwIfCancellation()
            val errorMsg = "Job '${job.name}' failed: ${e.message}"
            logger.error(e) { errorMsg }
            val durationMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
            recordExecution(job, JobExecutionStatus.FAILED, errorMsg, durationMs, true, startedAt)
            errorMsg
        }
    }

    private fun runJobWithRetryAndTimeout(job: ScheduledJob): String = runBlocking(Dispatchers.IO) {
        val timeoutMs = job.executionTimeoutMs
            ?: schedulerProperties.defaultExecutionTimeoutMs
        try {
            withTimeout(timeoutMs) { runWithRetry(job) }
        } catch (@Suppress("SwallowedException") e: kotlinx.coroutines.TimeoutCancellationException) {
            // TimeoutCancellationException은 withTimeout 내부의 CancellationException이며
            // runBlocking 경계 바깥으로 전파하면 코루틴 취소 시맨틱이 파괴된다.
            // RuntimeException으로 래핑하여 상위 catch 블록이 FAILED 상태로 기록하도록 한다.
            throw RuntimeException("Job '${job.name}' timed out after ${timeoutMs}ms")
        }
    }

    private suspend fun runWithRetry(job: ScheduledJob): String {
        if (!job.retryOnFailure) return executeJobContent(job)
        var lastException: Exception? = null
        for (attempt in 1..job.maxRetryCount.coerceAtLeast(1)) {
            try {
                return executeJobContent(job)
            } catch (e: Exception) {
                e.throwIfCancellation()
                lastException = e
                if (attempt < job.maxRetryCount) {
                    logger.warn { "Retrying job '${job.name}' attempt $attempt/${job.maxRetryCount}" }
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        throw lastException ?: IllegalStateException("All retries failed for job '${job.name}'")
    }

    private suspend fun executeJobContent(job: ScheduledJob): String = when (job.jobType) {
        ScheduledJobType.MCP_TOOL -> executeMcpToolJobSuspend(job)
        ScheduledJobType.AGENT -> executeAgentJobSuspend(job)
    }

    // -- MCP_TOOL mode ----------------------------------------------------------

    private suspend fun executeMcpToolJobSuspend(job: ScheduledJob): String {
        val serverName = job.mcpServerName
            ?: throw IllegalStateException("mcpServerName is required for MCP_TOOL job: ${job.name}")
        val toolName = job.toolName
            ?: throw IllegalStateException("toolName is required for MCP_TOOL job: ${job.name}")

        val connected = mcpManager.ensureConnected(serverName)
        if (!connected) {
            throw IllegalStateException("MCP server '$serverName' is not connected")
        }

        val tools = mcpManager.getToolCallbacks(serverName)
        val tool = tools.find { it.name == toolName }
            ?: throw IllegalStateException("Tool '$toolName' not found on server '$serverName'")

        return executeToolWithPolicies(job, tool)
    }

    private suspend fun executeToolWithPolicies(job: ScheduledJob, tool: ToolCallback): String {
        val baseArguments: Map<String, Any?> = job.toolArguments.mapValues { (_, v) ->
            if (v is String) resolveTemplateVariables(v, job) else v
        }
        val hookContext = buildHookContext(job)

        checkBeforeToolCall(
            ToolCallContext(
                agentContext = hookContext,
                toolName = tool.name,
                toolParams = baseArguments,
                callIndex = 0
            )
        )?.let { rejection ->
            throw IllegalStateException("Tool call rejected: ${rejection.reason}")
        }

        val effectiveArguments = resolveApprovedArguments(tool, baseArguments, hookContext)
        val toolCallContext = ToolCallContext(
            agentContext = hookContext,
            toolName = tool.name,
            toolParams = effectiveArguments,
            callIndex = 0
        )

        val startedAt = System.currentTimeMillis()
        return try {
            val output = tool.call(effectiveArguments)?.toString() ?: "No result"
            hookExecutor?.executeAfterToolCall(
                context = toolCallContext,
                result = ToolCallResult(
                    success = true,
                    output = output,
                    durationMs = System.currentTimeMillis() - startedAt
                )
            )
            output
        } catch (e: Exception) {
            e.throwIfCancellation()
            hookExecutor?.executeAfterToolCall(
                context = toolCallContext,
                result = ToolCallResult(
                    success = false,
                    errorMessage = e.message,
                    durationMs = System.currentTimeMillis() - startedAt
                )
            )
            throw e
        }
    }

    private suspend fun resolveApprovedArguments(
        tool: ToolCallback,
        arguments: Map<String, Any?>,
        hookContext: HookContext
    ): Map<String, Any?> {
        if (toolApprovalPolicy == null) return arguments
        if (!toolApprovalPolicy.requiresApproval(tool.name, arguments)) return arguments

        val approvalStore = pendingApprovalStore
        if (approvalStore == null) {
            val message = "Approval store unavailable for required scheduled tool '${tool.name}'"
            logger.error { message }
            throw IllegalStateException(message)
        }

        return try {
            val response = approvalStore.requestApproval(
                runId = hookContext.runId,
                userId = hookContext.userId,
                toolName = tool.name,
                arguments = arguments
            )
            if (response.approved) {
                response.modifiedArguments ?: arguments
            } else {
                val reason = response.reason ?: "Rejected by human"
                throw IllegalStateException("Tool call rejected by human: $reason")
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            e.throwIfCancellation()
            val message = "Approval check failed for scheduled tool '${tool.name}'"
            logger.error(e) { message }
            throw IllegalStateException(message, e)
        }
    }

    private suspend fun checkBeforeToolCall(context: ToolCallContext): HookResult.Reject? {
        if (hookExecutor == null) return null
        return hookExecutor.executeBeforeToolCall(context) as? HookResult.Reject
    }

    // -- AGENT mode -------------------------------------------------------------

    private suspend fun executeAgentJobSuspend(job: ScheduledJob): String {
        val executor = agentExecutor
            ?: agentExecutorProvider?.invoke()
            ?: throw IllegalStateException("AgentExecutor not available for AGENT job '${job.name}'. " +
                "Ensure the agent bean is configured.")

        val prompt = job.agentPrompt
            ?: throw IllegalStateException("agentPrompt is required for AGENT job '${job.name}'")

        val resolvedPrompt = resolveTemplateVariables(prompt, job)
        val command = buildAgentCommand(job, resolvedPrompt)

        val result = executor.execute(command)
        return if (result.success) {
            result.content ?: "Agent completed with no content"
        } else {
            throw IllegalStateException("Agent execution failed: ${result.errorMessage}")
        }
    }

    private fun buildAgentCommand(job: ScheduledJob, resolvedPrompt: String): AgentCommand =
        AgentCommand(
            systemPrompt = resolveSystemPrompt(job),
            userPrompt = resolvedPrompt,
            model = job.agentModel,
            maxToolCalls = job.agentMaxToolCalls ?: 10,
            userId = SCHEDULER_ACTOR,
            metadata = mapOf(
                "schedulerJobId" to job.id,
                "schedulerJobName" to job.name,
                "channel" to SCHEDULER_CHANNEL
            )
        )

    private fun resolveSystemPrompt(job: ScheduledJob): String {
        job.agentSystemPrompt?.let { if (it.isNotBlank()) return it }
        job.personaId?.let { id ->
            personaStore?.get(id)?.resolveEffectivePrompt(promptTemplateStore)?.let { return it }
        }
        personaStore?.getDefault()?.resolveEffectivePrompt(promptTemplateStore)?.let { return it }
        return DEFAULT_SYSTEM_PROMPT
    }

    // -- Shared helpers ---------------------------------------------------------

    private fun resolveTemplateVariables(template: String, job: ScheduledJob): String {
        val now = LocalDateTime.now(ZoneId.of(job.timezone))
        return template
            .replace("{{date}}", now.format(DateTimeFormatter.ISO_LOCAL_DATE))
            .replace("{{time}}", now.format(DateTimeFormatter.ISO_LOCAL_TIME).substringBefore("."))
            .replace("{{datetime}}", now.format(DATE_TIME_FORMATTER))
            .replace("{{day_of_week}}", now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            .replace("{{job_name}}", job.name)
            .replace("{{job_id}}", job.id)
    }

    private fun recordExecution(
        job: ScheduledJob,
        status: JobExecutionStatus,
        result: String?,
        durationMs: Long,
        dryRun: Boolean,
        startedAt: Instant
    ) {
        val execStore = executionStore ?: return
        val execution = buildExecutionRecord(job, status, result, durationMs, dryRun, startedAt)
        try {
            execStore.save(execution)
            cleanupOldExecutions(execStore, job.id)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to record execution history for job: ${job.name}" }
        }
    }

    private fun buildExecutionRecord(
        job: ScheduledJob,
        status: JobExecutionStatus,
        result: String?,
        durationMs: Long,
        dryRun: Boolean,
        startedAt: Instant
    ): ScheduledJobExecution = ScheduledJobExecution(
        id = "exec-${job.id}-${System.currentTimeMillis()}",
        jobId = job.id,
        jobName = job.name,
        status = status,
        result = result,
        durationMs = durationMs,
        dryRun = dryRun,
        startedAt = startedAt,
        completedAt = Instant.now()
    )

    private fun cleanupOldExecutions(execStore: ScheduledJobExecutionStore, jobId: String) {
        val maxPerJob = schedulerProperties.maxExecutionsPerJob
        if (maxPerJob <= 0) return
        try {
            execStore.deleteOldestExecutions(jobId, maxPerJob)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to cleanup old executions for job: $jobId" }
        }
    }

    private fun buildHookContext(job: ScheduledJob): HookContext {
        val runId = "scheduler-${job.id}-${System.currentTimeMillis()}"
        return HookContext(
            runId = runId,
            userId = SCHEDULER_ACTOR,
            userPrompt = "Scheduled job '${job.name}'",
            channel = SCHEDULER_CHANNEL
        ).also { context ->
            context.metadata["schedulerJobId"] = job.id
            context.metadata["schedulerJobName"] = job.name
            context.metadata["schedulerJobType"] = job.jobType.name
            if (job.jobType == ScheduledJobType.MCP_TOOL) {
                context.metadata["schedulerMcpServer"] = job.mcpServerName.orEmpty()
            }
        }
    }

    private fun sendSlackIfConfigured(job: ScheduledJob, result: String) {
        if (job.slackChannelId.isNullOrBlank() || slackMessageSender == null) return
        try {
            slackMessageSender.sendMessage(job.slackChannelId, formatSlackMessage(job, result))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send Slack message for job: ${job.name}" }
        }
    }

    private fun formatSlackMessage(job: ScheduledJob, result: String): String {
        val truncated = truncateMessage(result)
        return when (job.jobType) {
            ScheduledJobType.MCP_TOOL -> "*[${job.name}]* scheduled task result:\n```\n$truncated\n```"
            ScheduledJobType.AGENT -> "*[${job.name}]* \uBE0C\uB9AC\uD551:\n$truncated"
        }
    }

    private fun sendTeamsIfConfigured(job: ScheduledJob, result: String) {
        if (job.teamsWebhookUrl.isNullOrBlank() || teamsMessageSender == null) return
        try {
            teamsMessageSender.sendMessage(job.teamsWebhookUrl, formatTeamsMessage(job, result))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send Teams message for job: ${job.name}" }
        }
    }

    private fun formatTeamsMessage(job: ScheduledJob, result: String): String {
        val truncated = truncateMessage(result)
        return when (job.jobType) {
            ScheduledJobType.MCP_TOOL -> "**[${job.name}]** scheduled task result:\n```\n$truncated\n```"
            ScheduledJobType.AGENT -> "**[${job.name}]** \uBE0C\uB9AC\uD551:\n$truncated"
        }
    }

    private fun truncateMessage(text: String): String =
        if (text.length > MESSAGE_TRUNCATION_LIMIT) text.take(MESSAGE_TRUNCATION_LIMIT) + "\n..." else text

    private fun markSchedulingFailure(job: ScheduledJob, message: String) {
        store.updateExecutionResult(job.id, JobExecutionStatus.FAILED, message)
    }
}

/**
 * Slack 메시지 전송 인터페이스.
 * 스케줄러와 arc-slack 모듈의 의존성을 분리한다.
 *
 * WHY: 스케줄러가 Slack SDK에 직접 의존하지 않고,
 * 실행 결과를 Slack으로 전달할 수 있게 한다.
 *
 * @see DynamicSchedulerService 스케줄러에서의 활용
 * @see TeamsMessageSender Teams 메시지 전송 인터페이스
 */
fun interface SlackMessageSender {
    fun sendMessage(channelId: String, text: String)
}
