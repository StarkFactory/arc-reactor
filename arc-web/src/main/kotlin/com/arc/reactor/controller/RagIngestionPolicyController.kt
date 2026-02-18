package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.rag.ingestion.RagIngestionPolicy
import com.arc.reactor.rag.ingestion.RagIngestionPolicyProvider
import com.arc.reactor.rag.ingestion.RagIngestionPolicyStore
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

@Tag(name = "RAG Ingestion Policy", description = "Dynamic RAG ingestion capture policy (ADMIN only)")
@RestController
@RequestMapping("/api/rag-ingestion/policy")
@ConditionalOnProperty(
    prefix = "arc.reactor.rag.ingestion.dynamic", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class RagIngestionPolicyController(
    private val properties: AgentProperties,
    private val store: RagIngestionPolicyStore,
    private val provider: RagIngestionPolicyProvider,
    private val adminAuditStore: AdminAuditStore
) {

    @Operation(summary = "Get RAG ingestion policy state (effective + stored) (ADMIN)")
    @GetMapping
    fun get(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(
            RagIngestionPolicyStateResponse(
                configEnabled = properties.rag.ingestion.enabled,
                dynamicEnabled = properties.rag.ingestion.dynamic.enabled,
                effective = provider.current().toResponse(),
                stored = store.getOrNull()?.toResponse()
            )
        )
    }

    @Operation(summary = "Update stored RAG ingestion policy (ADMIN)")
    @PutMapping
    fun update(
        @Valid @RequestBody request: UpdateRagIngestionPolicyRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val saved = store.save(request.toPolicy())
        provider.invalidate()
        recordAdminAudit(
            store = adminAuditStore,
            category = "rag_ingestion_policy",
            action = "UPDATE",
            actor = currentActor(exchange),
            resourceType = "rag_ingestion_policy",
            resourceId = "singleton",
            detail = "enabled=${saved.enabled}, requireReview=${saved.requireReview}, " +
                "allowedChannels=${saved.allowedChannels.size}"
        )
        return ResponseEntity.ok(saved.toResponse())
    }

    @Operation(summary = "Delete stored RAG ingestion policy (reset to config defaults) (ADMIN)")
    @DeleteMapping
    fun delete(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        store.delete()
        provider.invalidate()
        recordAdminAudit(
            store = adminAuditStore,
            category = "rag_ingestion_policy",
            action = "DELETE",
            actor = currentActor(exchange),
            resourceType = "rag_ingestion_policy",
            resourceId = "singleton",
            detail = "reset_to_config_defaults=true"
        )
        return ResponseEntity.noContent().build()
    }
}

data class RagIngestionPolicyStateResponse(
    val configEnabled: Boolean,
    val dynamicEnabled: Boolean,
    val effective: RagIngestionPolicyResponse,
    val stored: RagIngestionPolicyResponse?
)

data class RagIngestionPolicyResponse(
    val enabled: Boolean,
    val requireReview: Boolean,
    val allowedChannels: Set<String>,
    val minQueryChars: Int,
    val minResponseChars: Int,
    val blockedPatterns: Set<String>,
    val createdAt: Long,
    val updatedAt: Long
)

data class UpdateRagIngestionPolicyRequest(
    val enabled: Boolean = false,
    val requireReview: Boolean = true,
    @field:Size(max = 300, message = "allowedChannels must not exceed 300 entries")
    val allowedChannels: Set<String> = emptySet(),
    val minQueryChars: Int = 10,
    val minResponseChars: Int = 20,
    @field:Size(max = 200, message = "blockedPatterns must not exceed 200 entries")
    val blockedPatterns: Set<String> = emptySet()
) {
    fun toPolicy(): RagIngestionPolicy = RagIngestionPolicy(
        enabled = enabled,
        requireReview = requireReview,
        allowedChannels = allowedChannels.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet(),
        minQueryChars = minQueryChars.coerceAtLeast(1),
        minResponseChars = minResponseChars.coerceAtLeast(1),
        blockedPatterns = blockedPatterns.map { it.trim() }.filter { it.isNotBlank() }.toSet()
    )
}

private fun RagIngestionPolicy.toResponse(): RagIngestionPolicyResponse = RagIngestionPolicyResponse(
    enabled = enabled,
    requireReview = requireReview,
    allowedChannels = allowedChannels,
    minQueryChars = minQueryChars,
    minResponseChars = minResponseChars,
    blockedPatterns = blockedPatterns,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)
