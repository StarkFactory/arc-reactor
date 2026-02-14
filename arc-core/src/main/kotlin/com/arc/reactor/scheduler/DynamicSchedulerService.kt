package com.arc.reactor.scheduler

import com.arc.reactor.mcp.McpManager
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
    private val slackMessageSender: SlackMessageSender? = null
) {

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
                logger.info { "Registered cron job: ${job.name} [${job.cronExpression}] → ${job.mcpServerName}/${job.toolName}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to register cron job: ${job.name}" }
        }
    }

    private fun cancelJob(id: String) {
        scheduledFutures.remove(id)?.cancel(false)
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

                tool.call(job.toolArguments)?.toString() ?: "No result"
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
