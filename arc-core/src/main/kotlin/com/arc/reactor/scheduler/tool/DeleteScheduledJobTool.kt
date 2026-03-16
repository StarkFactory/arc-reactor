package com.arc.reactor.scheduler.tool

import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.tool.LocalTool
import mu.KotlinLogging
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

private val logger = KotlinLogging.logger {}

/**
 * ID 또는 이름으로 스케줄 작업을 삭제하는 에이전트 도구.
 *
 * @param schedulerService 스케줄러 서비스
 * @see DynamicSchedulerService 스케줄러 서비스
 */
class DeleteScheduledJobTool(
    private val schedulerService: DynamicSchedulerService
) : LocalTool {

    @Tool(description = "Delete a scheduled job. Provide either the job ID or the job name.")
    fun delete_scheduled_job(
        @ToolParam(description = "ID of the scheduled job to delete (optional if jobName is provided)", required = false)
        jobId: String? = null,
        @ToolParam(description = "Name of the scheduled job to delete (optional if jobId is provided)", required = false)
        jobName: String? = null
    ): String {
        val trimmedId = jobId?.trim()?.ifBlank { null }
        val trimmedName = jobName?.trim()?.ifBlank { null }

        // jobId 또는 jobName 중 하나는 필수
        if (trimmedId == null && trimmedName == null) {
            return errorJson("Either jobId or jobName is required")
        }

        return try {
            // ID 우선, 없으면 이름으로 작업을 찾는다
            val job = when {
                trimmedId != null -> schedulerService.findById(trimmedId)
                    ?: return errorJson("Job not found with id: $trimmedId")
                else -> schedulerService.findByName(trimmedName ?: return errorJson("jobName is required"))
                    ?: return errorJson("Job not found with name: $trimmedName")
            }

            schedulerService.delete(job.id)
            toJson(mapOf("status" to "deleted", "id" to job.id, "name" to job.name))
        } catch (e: Exception) {
            logger.warn(e) { "스케줄 작업 삭제 실패: ${trimmedId ?: trimmedName}" }
            errorJson(e.message ?: "Failed to delete scheduled job")
        }
    }
}
