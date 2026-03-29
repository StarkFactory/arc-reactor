package com.arc.reactor.controller

import com.arc.reactor.audit.recordAdminAudit
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.rag.ingestion.RagIngestionPolicy
import com.arc.reactor.rag.ingestion.RagIngestionPolicyProvider
import com.arc.reactor.rag.ingestion.RagIngestionPolicyStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import java.util.regex.Pattern as JavaPattern
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * RAG Ingestion Policy 동적 관리 컨트롤러.
 *
 * RAG 수집 캡처 정책(허용 채널, 최소 글자 수, 차단 패턴 등)을 런타임에 관리합니다.
 * 저장된 정책을 삭제하면 설정 파일 기본값으로 복원됩니다.
 *
 * @see RagIngestionPolicyStore
 * @see RagIngestionPolicyProvider
 */
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

    /** 현재 적용 중인 정책과 저장된 정책 상태를 조회한다. */
    @Operation(summary = "RAG Ingestion Policy 상태 조회 (적용 중 + 저장) (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Current RAG ingestion policy state"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
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

    /** 저장된 RAG Ingestion Policy를 수정한다. */
    @Operation(summary = "저장된 RAG Ingestion Policy 수정 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "RAG ingestion policy updated"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PutMapping
    fun update(
        @Valid @RequestBody request: UpdateRagIngestionPolicyRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        validateBlockedPatterns(request.blockedPatterns)
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

    /** 저장된 RAG Ingestion Policy를 삭제하고 설정 기본값으로 복원한다. */
    @Operation(summary = "저장된 RAG Ingestion Policy 삭제 (설정 기본값 복원) (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "RAG ingestion policy deleted, reset to config defaults"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
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

/** 각 blockedPattern 항목의 길이와 정규식 유효성을 검증한다. */
private fun validateBlockedPatterns(patterns: Set<String>) {
    for (pattern in patterns) {
        require(pattern.length <= 500) { "각 blockedPattern은 500자 이하여야 합니다" }
        runCatching { JavaPattern.compile(pattern) }.onFailure {
            throw IllegalArgumentException("유효하지 않은 정규식 패턴: ${pattern.take(30)}...")
        }
    }
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
