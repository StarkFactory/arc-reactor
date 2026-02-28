package com.arc.reactor.controller

import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.ScheduledJob
import com.arc.reactor.scheduler.ScheduledJobType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
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
 * Provides REST APIs for managing scheduled jobs. Admin-only access when auth is enabled.
 *
 * ## Job Types
 * - **MCP_TOOL** (default): Invokes a single MCP tool on a schedule.
 * - **AGENT**: Runs the full ReAct agent loop and produces a natural-language result.
 *
 * ## Endpoints
 * - GET    /api/scheduler/jobs              : List all scheduled jobs
 * - POST   /api/scheduler/jobs              : Create a new scheduled job
 * - GET    /api/scheduler/jobs/{id}         : Get job details
 * - PUT    /api/scheduler/jobs/{id}         : Update a job
 * - DELETE /api/scheduler/jobs/{id}         : Delete a job
 * - POST   /api/scheduler/jobs/{id}/trigger : Trigger immediate execution
 */
@Tag(name = "Scheduler", description = "Dynamic scheduled job execution (ADMIN only)")
@RestController
@RequestMapping("/api/scheduler/jobs")
@ConditionalOnBean(DynamicSchedulerService::class)
class SchedulerController(
    private val schedulerService: DynamicSchedulerService
) {

    @Operation(summary = "List all scheduled jobs")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of scheduled jobs"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun listJobs(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(schedulerService.list().map { it.toResponse() })
    }

    @Operation(summary = "Create a new scheduled job (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Scheduled job created"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping
    fun createJob(
        @Valid @RequestBody request: CreateScheduledJobRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val job = try {
            schedulerService.create(request.toScheduledJob())
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse(error = e.message ?: "Invalid request", timestamp = Instant.now().toString()))
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(job.toResponse())
    }

    @Operation(summary = "Get scheduled job details")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Scheduled job details"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Scheduled job not found")
    ])
    @GetMapping("/{id}")
    fun getJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val job = schedulerService.findById(id) ?: return jobNotFound(id)
        return ResponseEntity.ok(job.toResponse())
    }

    @Operation(summary = "Update a scheduled job (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Scheduled job updated"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Scheduled job not found")
    ])
    @PutMapping("/{id}")
    fun updateJob(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateScheduledJobRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val updated = try {
            schedulerService.update(id, request.toScheduledJob()) ?: return jobNotFound(id)
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse(error = e.message ?: "Invalid request", timestamp = Instant.now().toString()))
        }
        return ResponseEntity.ok(updated.toResponse())
    }

    @Operation(summary = "Delete a scheduled job (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Scheduled job deleted"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Scheduled job not found")
    ])
    @DeleteMapping("/{id}")
    fun deleteJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        schedulerService.findById(id) ?: return jobNotFound(id)
        schedulerService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "Trigger immediate execution of a scheduled job (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Job triggered"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Scheduled job not found")
    ])
    @PostMapping("/{id}/trigger")
    fun triggerJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
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
        jobType = jobType.name,
        mcpServerName = mcpServerName,
        toolName = toolName,
        toolArguments = toolArguments,
        agentPrompt = agentPrompt,
        personaId = personaId,
        agentSystemPrompt = agentSystemPrompt,
        agentModel = agentModel,
        agentMaxToolCalls = agentMaxToolCalls,
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

    /** MCP_TOOL (default) or AGENT */
    val jobType: String = "MCP_TOOL",

    // MCP_TOOL mode
    val mcpServerName: String? = null,
    val toolName: String? = null,
    val toolArguments: Map<String, Any> = emptyMap(),

    // AGENT mode
    val agentPrompt: String? = null,
    val personaId: String? = null,
    val agentSystemPrompt: String? = null,
    val agentModel: String? = null,
    val agentMaxToolCalls: Int? = null,

    val slackChannelId: String? = null,
    val enabled: Boolean = true
) {
    fun toScheduledJob(): ScheduledJob {
        val type = parseJobType(jobType)
        validate(type)
        return ScheduledJob(
            name = name,
            description = description,
            cronExpression = cronExpression,
            timezone = timezone,
            jobType = type,
            mcpServerName = mcpServerName,
            toolName = toolName,
            toolArguments = toolArguments,
            agentPrompt = agentPrompt,
            personaId = personaId,
            agentSystemPrompt = agentSystemPrompt,
            agentModel = agentModel,
            agentMaxToolCalls = agentMaxToolCalls,
            slackChannelId = slackChannelId,
            enabled = enabled
        )
    }

    private fun validate(type: ScheduledJobType) {
        when (type) {
            ScheduledJobType.MCP_TOOL -> {
                require(!mcpServerName.isNullOrBlank()) { "mcpServerName is required for MCP_TOOL jobs" }
                require(!toolName.isNullOrBlank()) { "toolName is required for MCP_TOOL jobs" }
            }
            ScheduledJobType.AGENT -> {
                require(!agentPrompt.isNullOrBlank()) { "agentPrompt is required for AGENT jobs" }
            }
        }
    }
}

data class UpdateScheduledJobRequest(
    @field:NotBlank(message = "Job name is required")
    val name: String,
    val description: String? = null,
    @field:NotBlank(message = "Cron expression is required")
    val cronExpression: String,
    val timezone: String = "Asia/Seoul",

    val jobType: String = "MCP_TOOL",

    // MCP_TOOL mode
    val mcpServerName: String? = null,
    val toolName: String? = null,
    val toolArguments: Map<String, Any> = emptyMap(),

    // AGENT mode
    val agentPrompt: String? = null,
    val personaId: String? = null,
    val agentSystemPrompt: String? = null,
    val agentModel: String? = null,
    val agentMaxToolCalls: Int? = null,

    val slackChannelId: String? = null,
    val enabled: Boolean = true
) {
    fun toScheduledJob(): ScheduledJob {
        val type = parseJobType(jobType)
        when (type) {
            ScheduledJobType.MCP_TOOL -> {
                require(!mcpServerName.isNullOrBlank()) { "mcpServerName is required for MCP_TOOL jobs" }
                require(!toolName.isNullOrBlank()) { "toolName is required for MCP_TOOL jobs" }
            }
            ScheduledJobType.AGENT -> {
                require(!agentPrompt.isNullOrBlank()) { "agentPrompt is required for AGENT jobs" }
            }
        }
        return ScheduledJob(
            name = name,
            description = description,
            cronExpression = cronExpression,
            timezone = timezone,
            jobType = type,
            mcpServerName = mcpServerName,
            toolName = toolName,
            toolArguments = toolArguments,
            agentPrompt = agentPrompt,
            personaId = personaId,
            agentSystemPrompt = agentSystemPrompt,
            agentModel = agentModel,
            agentMaxToolCalls = agentMaxToolCalls,
            slackChannelId = slackChannelId,
            enabled = enabled
        )
    }
}

data class ScheduledJobResponse(
    val id: String,
    val name: String,
    val description: String?,
    val cronExpression: String,
    val timezone: String,
    val jobType: String,
    val mcpServerName: String?,
    val toolName: String?,
    val toolArguments: Map<String, Any>,
    val agentPrompt: String?,
    val personaId: String?,
    val agentSystemPrompt: String?,
    val agentModel: String?,
    val agentMaxToolCalls: Int?,
    val slackChannelId: String?,
    val enabled: Boolean,
    val lastRunAt: Long?,
    val lastStatus: String?,
    val lastResult: String?,
    val createdAt: Long,
    val updatedAt: Long
)

private fun parseJobType(value: String): ScheduledJobType =
    try {
        ScheduledJobType.valueOf(value.uppercase())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid jobType '$value'. Must be one of: ${ScheduledJobType.entries.joinToString()}")
    }
