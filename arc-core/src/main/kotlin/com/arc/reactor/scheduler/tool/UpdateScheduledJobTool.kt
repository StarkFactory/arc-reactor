package com.arc.reactor.scheduler.tool

import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.tool.LocalTool
import mu.KotlinLogging
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

private val logger = KotlinLogging.logger {}

/**
 * 기존 스케줄 작업을 갱신하는 에이전트 도구.
 *
 * 부분 갱신을 지원한다 — 지정된 필드만 변경되고,
 * 미지정 필드는 현재 값을 유지한다.
 *
 * WHY: 사용자가 자연어로 "크론을 9시에서 10시로 바꿔줘" 같은
 * 부분 수정 요청을 할 수 있게 한다.
 *
 * @param schedulerService 스케줄러 서비스
 * @see DynamicSchedulerService 스케줄러 서비스
 */
class UpdateScheduledJobTool(
    private val schedulerService: DynamicSchedulerService
) : LocalTool {

    @Tool(
        description = """Update an existing scheduled job. Only provide the fields you want to change.
Use list_scheduled_jobs to find the job first. You can change the cron schedule, prompt, Slack channel, timezone, or enable/disable the job.
Cron format is Spring 6-field: "second minute hour day month weekday".
NOTE: Changing agentPrompt alters what the job executes on every future run. Confirm with the user before modifying prompts."""
    )
    fun update_scheduled_job(
        @ToolParam(description = "ID of the job to update (optional if jobName is provided)", required = false)
        jobId: String? = null,
        @ToolParam(description = "Name of the job to update (optional if jobId is provided)", required = false)
        jobName: String? = null,
        @ToolParam(description = "New cron expression (Spring 6-field)", required = false)
        cronExpression: String? = null,
        @ToolParam(description = "New prompt to execute on each run", required = false)
        agentPrompt: String? = null,
        @ToolParam(description = "New Slack channel ID for results", required = false)
        slackChannelId: String? = null,
        @ToolParam(description = "New timezone", required = false)
        timezone: String? = null,
        @ToolParam(description = "Enable or disable the job", required = false)
        enabled: Boolean? = null
    ): String {
        val trimmedId = jobId?.trim()?.ifBlank { null }
        val trimmedName = jobName?.trim()?.ifBlank { null }

        // jobId 또는 jobName 중 하나는 필수
        if (trimmedId == null && trimmedName == null) {
            return errorJson("Either jobId or jobName is required")
        }

        return try {
            // 기존 작업을 찾는다
            val existing = when {
                trimmedId != null -> schedulerService.findById(trimmedId)
                    ?: return errorJson("Job not found with id: $trimmedId")
                else -> schedulerService.findByName(trimmedName ?: return errorJson("jobName is required"))
                    ?: return errorJson("Job not found with name: $trimmedName")
            }

            // 지정된 필드만 갱신하고 나머지는 기존 값 유지
            val updated = existing.copy(
                cronExpression = cronExpression?.trim() ?: existing.cronExpression,
                agentPrompt = agentPrompt?.trim() ?: existing.agentPrompt,
                slackChannelId = if (slackChannelId != null) slackChannelId.trim().ifBlank { null } else existing.slackChannelId,
                timezone = timezone?.trim() ?: existing.timezone,
                enabled = enabled ?: existing.enabled
            )

            val result = schedulerService.update(existing.id, updated)
                ?: return errorJson("Failed to update job: ${existing.id}")

            toJson(
                mapOf(
                    "status" to "updated",
                    "id" to result.id,
                    "name" to result.name,
                    "cronExpression" to result.cronExpression,
                    "timezone" to result.timezone,
                    "enabled" to result.enabled,
                    "slackChannelId" to result.slackChannelId
                )
            )
        } catch (e: Exception) {
            logger.warn(e) { "스케줄 작업 갱신 실패: ${trimmedId ?: trimmedName}" }
            errorJson(e.message ?: "Failed to update scheduled job")
        }
    }
}
