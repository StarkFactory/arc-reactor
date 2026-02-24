package com.arc.reactor.scheduler

import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

private val logger = KotlinLogging.logger {}

/**
 * Dynamic Scheduler Service
 *
 * Manages cron-scheduled MCP tool executions at runtime.
 * Jobs can be created, updated, deleted, and triggered manually via REST API.
 *
 * On startup, loads all enabled jobs from the store and registers cron triggers.
 * Each job execution:
 * 1. Ensures the MCP server is connected
 * 2. Finds and invokes the target tool
 * 3. Optionally sends result to Slack via SlackMessageSender
 * 4. Records execution status in the store
 */
class DynamicSchedulerService(
    private val store: ScheduledJobStore,
    private val taskScheduler: TaskScheduler,
    private val mcpManager: McpManager,
    private val slackMessageSender: SlackMessageSender? = null,
    private val hookExecutor: HookExecutor? = null,
    private val toolApprovalPolicy: ToolApprovalPolicy? = null,
    private val pendingApprovalStore: PendingApprovalStore? = null
) {

    companion object {
        private const val SCHEDULER_ACTOR = "scheduler"
        private const val SCHEDULER_CHANNEL = "scheduler"
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
        val saved = store.save(job)
        if (saved.enabled) registerJob(saved)
        logger.info { "Scheduled job created: ${saved.name} (${saved.cronExpression})" }
        return saved
    }

    fun update(id: String, job: ScheduledJob): ScheduledJob? {
        cancelJob(id)
        val updated = store.update(id, job) ?: return null
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

    fun list(): List<ScheduledJob> = store.list()

    fun findById(id: String): ScheduledJob? = store.findById(id)

    private fun registerJob(job: ScheduledJob) {
        try {
            val zone = ZoneId.of(job.timezone)
            val trigger = CronTrigger(job.cronExpression, zone)
            val future = taskScheduler.schedule({ executeJob(job) }, trigger)
            if (future != null) {
                scheduledFutures[job.id] = future
                logger.info {
                    "Registered cron job: ${job.name} [${job.cronExpression}] " +
                        "→ ${job.mcpServerName}/${job.toolName}"
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to register cron job: ${job.name}" }
        }
    }

    private fun cancelJob(id: String) {
        scheduledFutures.remove(id)?.cancel(false)
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
            logger.warn {
                "Approval required for scheduled tool '${tool.name}' but PendingApprovalStore is unavailable; " +
                    "continuing fail-open"
            }
            return arguments
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
            logger.error(e) {
                "Approval check failed for scheduled tool '${tool.name}', continuing fail-open"
            }
            arguments
        }
    }

    private suspend fun checkBeforeToolCall(context: ToolCallContext): HookResult.Reject? {
        if (hookExecutor == null) return null
        return hookExecutor.executeBeforeToolCall(context) as? HookResult.Reject
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
            context.metadata["schedulerMcpServer"] = job.mcpServerName
        }
    }

    private fun executeJob(job: ScheduledJob): String {
        logger.info { "Executing scheduled job: ${job.name}" }
        store.updateExecutionResult(job.id, JobExecutionStatus.RUNNING, null)

        return try {
            val result = runBlocking {
                val connected = mcpManager.ensureConnected(job.mcpServerName)
                if (!connected) {
                    throw IllegalStateException("MCP server '${job.mcpServerName}' is not connected")
                }

                val tools = mcpManager.getToolCallbacks(job.mcpServerName)
                val tool = tools.find { it.name == job.toolName }
                    ?: throw IllegalStateException("Tool '${job.toolName}' not found on server '${job.mcpServerName}'")

                executeToolWithPolicies(job, tool)
            }

            // Send to Slack if configured
            if (!job.slackChannelId.isNullOrBlank() && slackMessageSender != null) {
                try {
                    slackMessageSender.sendMessage(job.slackChannelId, formatSlackMessage(job, result))
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to send Slack message for job: ${job.name}" }
                }
            }

            store.updateExecutionResult(job.id, JobExecutionStatus.SUCCESS, result)
            logger.info { "Scheduled job completed: ${job.name}" }
            result
        } catch (e: Exception) {
            val errorMsg = "Job '${job.name}' failed: ${e.message}"
            logger.error(e) { errorMsg }
            store.updateExecutionResult(job.id, JobExecutionStatus.FAILED, errorMsg)
            errorMsg
        }
    }

    private fun formatSlackMessage(job: ScheduledJob, result: String): String {
        val truncated = if (result.length > 3000) result.take(3000) + "\n..." else result
        return "*[${job.name}]* scheduled task result:\n```\n$truncated\n```"
    }
}

/**
 * Interface for sending Slack messages.
 * Decouples scheduler from arc-slack module dependency.
 */
interface SlackMessageSender {
    fun sendMessage(channelId: String, text: String)
}
