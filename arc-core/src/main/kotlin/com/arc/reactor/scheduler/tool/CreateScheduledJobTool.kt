package com.arc.reactor.scheduler.tool

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.ScheduledJob
import com.arc.reactor.scheduler.ScheduledJobType
import com.arc.reactor.tool.LocalTool
import mu.KotlinLogging
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

private val logger = KotlinLogging.logger {}

/**
 * Agent tool for creating scheduled jobs via natural language.
 *
 * The LLM converts user requests like "매일 9시에 요약해줘" into
 * a cron expression and appropriate parameters.
 */
class CreateScheduledJobTool(
    private val schedulerService: DynamicSchedulerService,
    private val defaultTimezone: String
) : LocalTool {

    @Tool(
        description = """Create a new scheduled job. The job runs on a cron schedule and executes an agent prompt.
Results can be sent to a Slack channel.
Cron format is Spring 6-field: "second minute hour day month weekday".
Examples: "0 0 9 * * *" (daily 9AM), "0 0 9 * * 1-5" (weekdays 9AM), "0 0 */2 * * *" (every 2h), "0 30 14 * * *" (daily 2:30PM).
IMPORTANT: AGENT mode schedules invoke the LLM on each execution, which incurs API costs."""
    )
    fun create_scheduled_job(
        @ToolParam(description = "Name for the scheduled job") name: String,
        @ToolParam(description = "Spring 6-field cron (e.g. '0 0 9 * * *' for daily 9AM). Format: second minute hour day month weekday") cronExpression: String,
        @ToolParam(description = "Prompt to execute on each run") agentPrompt: String,
        @ToolParam(description = "Slack channel ID to send results to (optional)", required = false)
        slackChannelId: String? = null,
        @ToolParam(description = "Timezone for the schedule (e.g. 'Asia/Seoul', 'UTC', 'America/New_York'). Uses server default if not specified.", required = false)
        timezone: String? = null
    ): String {
        if (name.isBlank()) return errorJson("name is required")
        if (cronExpression.isBlank()) return errorJson("cronExpression is required")
        if (agentPrompt.isBlank()) return errorJson("agentPrompt is required")

        val trimmedName = name.trim()
        val existing = schedulerService.findByName(trimmedName)
        if (existing != null) {
            return errorJson("A job with name '$trimmedName' already exists (id=${existing.id})")
        }

        return try {
            val job = schedulerService.create(
                ScheduledJob(
                    name = trimmedName,
                    cronExpression = cronExpression.trim(),
                    timezone = timezone?.trim() ?: defaultTimezone,
                    jobType = ScheduledJobType.AGENT,
                    agentPrompt = agentPrompt.trim(),
                    slackChannelId = slackChannelId?.trim()?.ifBlank { null }
                )
            )
            toJson(
                mapOf(
                    "status" to "created",
                    "id" to job.id,
                    "name" to job.name,
                    "cronExpression" to job.cronExpression,
                    "timezone" to job.timezone,
                    "slackChannelId" to job.slackChannelId
                )
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to create scheduled job: $trimmedName" }
            errorJson(e.message ?: "Failed to create scheduled job")
        }
    }
}
