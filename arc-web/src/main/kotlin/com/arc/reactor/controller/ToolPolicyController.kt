package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.policy.tool.ToolPolicy
import com.arc.reactor.policy.tool.ToolPolicyProvider
import com.arc.reactor.policy.tool.ToolPolicyStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@Tag(name = "Tool Policy", description = "Dynamic tool policy (ADMIN only)")
@RestController
@RequestMapping("/api/tool-policy")
@ConditionalOnProperty(
    prefix = "arc.reactor.tool-policy.dynamic", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class ToolPolicyController(
    private val properties: AgentProperties,
    private val store: ToolPolicyStore,
    private val provider: ToolPolicyProvider,
    private val adminAuditStore: AdminAuditStore
) {

    @Operation(summary = "Get tool policy state (effective + stored) (ADMIN)")
    @GetMapping
    fun get(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val effective = provider.current().toResponse()
        val stored = store.getOrNull()?.toResponse()
        return ResponseEntity.ok(
            ToolPolicyStateResponse(
                configEnabled = properties.toolPolicy.enabled,
                dynamicEnabled = properties.toolPolicy.dynamic.enabled,
                effective = effective,
                stored = stored
            )
        )
    }

    @Operation(summary = "Update stored tool policy (ADMIN)")
    @PutMapping
    fun update(
        @Valid @RequestBody request: UpdateToolPolicyRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val saved = store.save(request.toPolicy())
        provider.invalidate()
        recordAdminAudit(
            store = adminAuditStore,
            category = "tool_policy",
            action = "UPDATE",
            actor = currentActor(exchange),
            resourceType = "tool_policy",
            resourceId = "singleton",
            detail = "enabled=${saved.enabled}, writeTools=${saved.writeToolNames.size}, denyChannels=${saved.denyWriteChannels.size}"
        )
        return ResponseEntity.ok(saved.toResponse())
    }

    @Operation(summary = "Delete stored tool policy (reset to config defaults) (ADMIN)")
    @DeleteMapping
    fun delete(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        store.delete()
        provider.invalidate()
        recordAdminAudit(
            store = adminAuditStore,
            category = "tool_policy",
            action = "DELETE",
            actor = currentActor(exchange),
            resourceType = "tool_policy",
            resourceId = "singleton",
            detail = "reset_to_config_defaults=true"
        )
        return ResponseEntity.noContent().build()
    }
}

data class ToolPolicyStateResponse(
    val configEnabled: Boolean,
    val dynamicEnabled: Boolean,
    val effective: ToolPolicyResponse,
    val stored: ToolPolicyResponse?
)

data class ToolPolicyResponse(
    val enabled: Boolean,
    val writeToolNames: Set<String>,
    val denyWriteChannels: Set<String>,
    val allowWriteToolNamesInDenyChannels: Set<String>,
    val allowWriteToolNamesByChannel: Map<String, Set<String>>,
    val denyWriteMessage: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class UpdateToolPolicyRequest(
    val enabled: Boolean = false,
    @field:Size(max = 500, message = "writeToolNames must not exceed 500 entries")
    val writeToolNames: Set<String> = emptySet(),
    @field:Size(max = 50, message = "denyWriteChannels must not exceed 50 entries")
    val denyWriteChannels: Set<String> = emptySet(),
    @field:Size(max = 500, message = "allowWriteToolNamesInDenyChannels must not exceed 500 entries")
    val allowWriteToolNamesInDenyChannels: Set<String> = emptySet(),
    @field:Size(max = 200, message = "allowWriteToolNamesByChannel must not exceed 200 channels")
    val allowWriteToolNamesByChannel: Map<String, Set<String>> = emptyMap(),
    @field:Size(max = 500, message = "denyWriteMessage must not exceed 500 characters")
    val denyWriteMessage: String = "Error: This tool is not allowed in this channel"
) {
    fun toPolicy(): ToolPolicy = ToolPolicy(
        enabled = enabled,
        writeToolNames = writeToolNames.map { it.trim() }.filter { it.isNotBlank() }.toSet(),
        denyWriteChannels = denyWriteChannels.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet(),
        allowWriteToolNamesInDenyChannels = allowWriteToolNamesInDenyChannels.map { it.trim() }.filter { it.isNotBlank() }.toSet(),
        allowWriteToolNamesByChannel = allowWriteToolNamesByChannel
            .mapKeys { (k, _) -> k.trim().lowercase() }
            .mapValues { (_, v) -> v.map { it.trim() }.filter { it.isNotBlank() }.toSet() }
            .filterKeys { it.isNotBlank() }
            .filterValues { it.isNotEmpty() },
        denyWriteMessage = denyWriteMessage.trim()
    )
}

private fun ToolPolicy.toResponse(): ToolPolicyResponse = ToolPolicyResponse(
    enabled = enabled,
    writeToolNames = writeToolNames,
    denyWriteChannels = denyWriteChannels,
    allowWriteToolNamesInDenyChannels = allowWriteToolNamesInDenyChannels,
    allowWriteToolNamesByChannel = allowWriteToolNamesByChannel,
    denyWriteMessage = denyWriteMessage,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)
