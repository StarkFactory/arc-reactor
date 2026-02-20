package com.arc.reactor.controller

import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.ScheduledJob
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

/**
 * Dynamic Scheduler API Controller
 *
 * Provides REST APIs for managing scheduled MCP tool executions.
 * Admin-only access when auth is enabled.
 *
 * ## Endpoints
 * - GET    /api/scheduler/jobs            : List all scheduled jobs
 * - POST   /api/scheduler/jobs            : Create a new scheduled job
 * - GET    /api/scheduler/jobs/{id}       : Get job details
 * - PUT    /api/scheduler/jobs/{id}       : Update a job
 * - DELETE /api/scheduler/jobs/{id}       : Delete a job
 * - POST   /api/scheduler/jobs/{id}/trigger : Trigger immediate execution
 */
@Tag(name = "Scheduler", description = "Dynamic scheduled MCP tool execution (ADMIN only)")
@RestController
@RequestMapping("/api/scheduler/jobs")
@ConditionalOnBean(DynamicSchedulerService::class)
class SchedulerController(
    private val schedulerService: DynamicSchedulerService
) {

    @Operation(summary = "List all scheduled jobs")
    @GetMapping
    fun listJobs(): List<ScheduledJobResponse> {
        return schedulerService.list().map { it.toResponse() }
    }

    @Operation(summary = "Create a new scheduled job (ADMIN)")
    @PostMapping
    fun createJob(
        @Valid @RequestBody request: CreateScheduledJobRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val job = schedulerService.create(request.toScheduledJob())
        return ResponseEntity.status(HttpStatus.CREATED).body(job.toResponse())
    }

    @Operation(summary = "Get scheduled job details")
    @GetMapping("/{id}")
    fun getJob(@PathVariable id: String): ResponseEntity<Any> {
        val job = schedulerService.findById(id)
            ?: return jobNotFound(id)
        return ResponseEntity.ok(job.toResponse())
    }

    @Operation(summary = "Update a scheduled job (ADMIN)")
    @PutMapping("/{id}")
    fun updateJob(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateScheduledJobRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val updated = schedulerService.update(id, request.toScheduledJob())
            ?: return jobNotFound(id)
        return ResponseEntity.ok(updated.toResponse())
    }

    @Operation(summary = "Delete a scheduled job (ADMIN)")
    @DeleteMapping("/{id}")
    fun deleteJob(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        schedulerService.findById(id)
            ?: return jobNotFound(id)

        schedulerService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "Trigger immediate execution of a scheduled job (ADMIN)")
    @PostMapping("/{id}/trigger")
    fun triggerJob(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val result = schedulerService.trigger(id)
        return ResponseEntity.ok(mapOf("result" to result))
    }

    private fun jobNotFound(id: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(error = "Scheduled job not found: $id", timestamp = Instant.now().toString()))

    private fun ScheduledJob.toResponse() = ScheduledJobResponse(
        id = id,
        name = name,
        description = description,
        cronExpression = cronExpression,
        timezone = timezone,
        mcpServerName = mcpServerName,
        toolName = toolName,
        toolArguments = toolArguments,
        slackChannelId = slackChannelId,
        enabled = enabled,
        lastRunAt = lastRunAt?.toEpochMilli(),
        lastStatus = lastStatus?.name,
        lastResult = lastResult,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli()
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
    @field:NotBlank(message = "MCP server name is required")
    val mcpServerName: String,
    @field:NotBlank(message = "Tool name is required")
    val toolName: String,
    val toolArguments: Map<String, Any> = emptyMap(),
    val slackChannelId: String? = null,
    val enabled: Boolean = true
) {
    fun toScheduledJob() = ScheduledJob(
        name = name,
        description = description,
        cronExpression = cronExpression,
        timezone = timezone,
        mcpServerName = mcpServerName,
        toolName = toolName,
        toolArguments = toolArguments,
        slackChannelId = slackChannelId,
        enabled = enabled
    )
}

data class UpdateScheduledJobRequest(
    @field:NotBlank(message = "Job name is required")
    val name: String,
    val description: String? = null,
    @field:NotBlank(message = "Cron expression is required")
    val cronExpression: String,
    val timezone: String = "Asia/Seoul",
    @field:NotBlank(message = "MCP server name is required")
    val mcpServerName: String,
    @field:NotBlank(message = "Tool name is required")
    val toolName: String,
    val toolArguments: Map<String, Any> = emptyMap(),
    val slackChannelId: String? = null,
    val enabled: Boolean = true
) {
    fun toScheduledJob() = ScheduledJob(
        name = name,
        description = description,
        cronExpression = cronExpression,
        timezone = timezone,
        mcpServerName = mcpServerName,
        toolName = toolName,
        toolArguments = toolArguments,
        slackChannelId = slackChannelId,
        enabled = enabled
    )
}

data class ScheduledJobResponse(
    val id: String,
    val name: String,
    val description: String?,
    val cronExpression: String,
    val timezone: String,
    val mcpServerName: String,
    val toolName: String,
    val toolArguments: Map<String, Any>,
    val slackChannelId: String?,
    val enabled: Boolean,
    val lastRunAt: Long?,
    val lastStatus: String?,
    val lastResult: String?,
    val createdAt: Long,
    val updatedAt: Long
)
