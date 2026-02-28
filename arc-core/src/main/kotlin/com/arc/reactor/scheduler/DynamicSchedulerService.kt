package com.arc.reactor.scheduler

import com.arc.reactor.agent.AgentExecutor
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
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronExpression
import org.springframework.scheduling.support.CronTrigger
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

private val logger = KotlinLogging.logger {}

/**
 * Dynamic Scheduler Service
 *
 * Manages cron-scheduled job executions at runtime.
 * Jobs can be created, updated, deleted, and triggered manually via REST API.
 *
 * Supports two execution modes:
 * - **MCP_TOOL**: Directly invokes a single MCP tool (original behavior).
 * - **AGENT**: Runs the full ReAct agent loop, allowing the LLM to reason over
 *   multiple MCP tools and produce a natural-language summary.
 *
 * On startup, loads all enabled jobs from the store and registers cron triggers.
 * Each job execution optionally sends its result to a Slack or Teams channel.
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
    private val personaStore: PersonaStore? = null,
    private val executionStore: ScheduledJobExecutionStore? = null
) {

    companion object {
        private const val SCHEDULER_ACTOR = "scheduler"
        private const val SCHEDULER_CHANNEL = "scheduler"
        private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful AI assistant."
        private const val RETRY_DELAY_MS = 2000L
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
        try {
            ZoneId.of(job.timezone)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid timezone: ${job.timezone}", e)
        }
        try {
            CronExpression.parse(job.cronExpression)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid cron expression: ${job.cronExpression}", e)
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
            val errorMsg = "Job '${job.name}' failed: ${e.message}"
            logger.error(e) { errorMsg }
            val durationMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
            recordExecution(job, JobExecutionStatus.FAILED, errorMsg, durationMs, true, startedAt)
            errorMsg
        }
    }

    private fun runJobWithRetryAndTimeout(job: ScheduledJob): String = runBlocking {
        val timeoutMs = job.executionTimeoutMs
        if (timeoutMs != null) {
            try {
                withTimeout(timeoutMs) { runWithRetry(job) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw IllegalStateException("Job '${job.name}' timed out after ${timeoutMs}ms")
            }
        } else {
            runWithRetry(job)
        }
    }

    private suspend fun runWithRetry(job: ScheduledJob): String {
        if (!job.retryOnFailure) return executeJobContent(job)
        var lastException: Exception? = null
        for (attempt in 1..job.maxRetryCount) {
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

    private fun executeMcpToolJob(job: ScheduledJob): String = runBlocking {
        executeMcpToolJobSuspend(job)
    }

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
        val baseArguments: Map<String, Any?> = job.toolArguments.mapValues { it.value }
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

    private fun executeAgentJob(job: ScheduledJob): String = runBlocking {
        executeAgentJobSuspend(job)
    }

    private suspend fun executeAgentJobSuspend(job: ScheduledJob): String {
        val executor = agentExecutor
            ?: throw IllegalStateException("AgentExecutor not available for AGENT job '${job.name}'. " +
                "Ensure the agent bean is configured.")

        val prompt = job.agentPrompt
            ?: throw IllegalStateException("agentPrompt is required for AGENT job '${job.name}'")

        val command = AgentCommand(
            systemPrompt = resolveSystemPrompt(job),
            userPrompt = prompt,
            model = job.agentModel,
            maxToolCalls = job.agentMaxToolCalls ?: 10,
            userId = SCHEDULER_ACTOR,
            metadata = mapOf(
                "schedulerJobId" to job.id,
                "schedulerJobName" to job.name,
                "channel" to SCHEDULER_CHANNEL
            )
        )

        val result = executor.execute(command)
        return if (result.success) {
            result.content ?: "Agent completed with no content"
        } else {
            throw IllegalStateException("Agent execution failed: ${result.errorMessage}")
        }
    }

    private fun resolveSystemPrompt(job: ScheduledJob): String {
        job.agentSystemPrompt?.let { if (it.isNotBlank()) return it }
        job.personaId?.let { id ->
            personaStore?.get(id)?.systemPrompt?.let { return it }
        }
        personaStore?.getDefault()?.systemPrompt?.let { return it }
        return DEFAULT_SYSTEM_PROMPT
    }

    // -- Shared helpers ---------------------------------------------------------

    private fun recordExecution(
        job: ScheduledJob,
        status: JobExecutionStatus,
        result: String?,
        durationMs: Long,
        dryRun: Boolean,
        startedAt: Instant
    ) {
        val execStore = executionStore ?: return
        val execution = ScheduledJobExecution(
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
        try {
            execStore.save(execution)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to record execution history for job: ${job.name}" }
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
        val truncated = if (result.length > 3000) result.take(3000) + "\n..." else result
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
        val truncated = if (result.length > 3000) result.take(3000) + "\n..." else result
        return when (job.jobType) {
            ScheduledJobType.MCP_TOOL -> "**[${job.name}]** scheduled task result:\n```\n$truncated\n```"
            ScheduledJobType.AGENT -> "**[${job.name}]** \uBE0C\uB9AC\uD551:\n$truncated"
        }
    }

    private fun markSchedulingFailure(job: ScheduledJob, message: String) {
        store.updateExecutionResult(job.id, JobExecutionStatus.FAILED, message)
    }
}

/**
 * Interface for sending Slack messages.
 * Decouples scheduler from arc-slack module dependency.
 */
fun interface SlackMessageSender {
    fun sendMessage(channelId: String, text: String)
}
