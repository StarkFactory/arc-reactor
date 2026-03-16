package com.arc.reactor.scheduler.tool

import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.tool.LocalTool
import mu.KotlinLogging
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

private val logger = KotlinLogging.logger {}

/**
 * Agent tool for deleting a scheduled job by ID or name.
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

        if (trimmedId == null && trimmedName == null) {
            return errorJson("Either jobId or jobName is required")
        }

        return try {
            val job = when {
                trimmedId != null -> schedulerService.findById(trimmedId)
                    ?: return errorJson("Job not found with id: $trimmedId")
                else -> schedulerService.findByName(trimmedName ?: return errorJson("jobName is required"))
                    ?: return errorJson("Job not found with name: $trimmedName")
            }

            schedulerService.delete(job.id)
            toJson(mapOf("status" to "deleted", "id" to job.id, "name" to job.name))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete scheduled job: ${trimmedId ?: trimmedName}" }
            errorJson(e.message ?: "Failed to delete scheduled job")
        }
    }
}
