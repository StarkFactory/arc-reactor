package com.arc.reactor.scheduler.tool

import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.tool.LocalTool
import mu.KotlinLogging
import org.springframework.ai.tool.annotation.Tool

private val logger = KotlinLogging.logger {}

/**
 * Agent tool for listing all scheduled jobs.
 */
class ListScheduledJobsTool(
    private val schedulerService: DynamicSchedulerService
) : LocalTool {

    @Tool(description = "List all scheduled jobs with their cron expressions, status, and configuration.")
    fun list_scheduled_jobs(): String {
        return try {
            val jobs = schedulerService.list()
            val summary = jobs.map { job ->
                mapOf(
                    "id" to job.id,
                    "name" to job.name,
                    "cronExpression" to job.cronExpression,
                    "timezone" to job.timezone,
                    "jobType" to job.jobType.name,
                    "enabled" to job.enabled,
                    "slackChannelId" to job.slackChannelId,
                    "lastStatus" to job.lastStatus?.name,
                    "lastRunAt" to job.lastRunAt?.toString()
                )
            }
            toJson(mapOf("jobs" to summary, "total" to jobs.size))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to list scheduled jobs" }
            errorJson(e.message ?: "Failed to list scheduled jobs")
        }
    }
}
