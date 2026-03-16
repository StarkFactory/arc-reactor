package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.slack.proactive.ProactiveChannel
import com.arc.reactor.slack.proactive.ProactiveChannelStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@Tag(name = "Proactive Channels", description = "Manage proactive monitoring channels (ADMIN)")
@RestController
@RequestMapping("/api/proactive-channels")
@ConditionalOnProperty(
    prefix = "arc.reactor.slack", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@ConditionalOnBean(ProactiveChannelStore::class)
class ProactiveChannelController(
    private val store: ProactiveChannelStore,
    private val adminAuditStore: AdminAuditStore
) {

    @Operation(summary = "List all proactive channels (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of proactive channels"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun list(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(store.list().map { it.toResponse() })
    }

    @Operation(summary = "Add a channel to proactive monitoring (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Channel added"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "409", description = "Channel already exists")
    ])
    @PostMapping
    fun add(
        @Valid @RequestBody request: AddProactiveChannelRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        if (store.isEnabled(request.channelId)) {
            return conflictResponse("Channel already in proactive list")
        }

        val channel = store.add(request.channelId, request.channelName)
        recordAdminAudit(
            store = adminAuditStore,
            category = "proactive_channel",
            action = "ADD",
            actor = currentActor(exchange),
            resourceType = "proactive_channel",
            resourceId = request.channelId,
            detail = "channelName=${request.channelName.orEmpty()}"
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(channel.toResponse())
    }

    @Operation(summary = "Remove a channel from proactive monitoring (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Channel removed"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Channel not found")
    ])
    @DeleteMapping("/{channelId}")
    fun remove(
        @PathVariable channelId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        if (!store.remove(channelId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse(
                    error = "Channel not found in proactive list",
                    timestamp = java.time.Instant.now().toString()
                ))
        }

        recordAdminAudit(
            store = adminAuditStore,
            category = "proactive_channel",
            action = "REMOVE",
            actor = currentActor(exchange),
            resourceType = "proactive_channel",
            resourceId = channelId,
            detail = "removed"
        )
        return ResponseEntity.noContent().build()
    }
}

data class AddProactiveChannelRequest(
    @field:NotBlank(message = "channelId must not be blank")
    @field:Size(max = 50, message = "channelId must not exceed 50 characters")
    val channelId: String,

    @field:Size(max = 200, message = "channelName must not exceed 200 characters")
    val channelName: String? = null
)

data class ProactiveChannelResponse(
    val channelId: String,
    val channelName: String?,
    val addedAt: Long
)

private fun ProactiveChannel.toResponse() = ProactiveChannelResponse(
    channelId = channelId,
    channelName = channelName,
    addedAt = addedAt
)
