package com.arc.reactor.controller

import com.arc.reactor.audit.recordAdminAudit
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.McpSecurityPolicy
import com.arc.reactor.mcp.McpSecurityPolicyProvider
import com.arc.reactor.mcp.McpSecurityPolicyStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * MCP Security Policy 동적 관리 컨트롤러.
 *
 * MCP 서버 허용 목록(allowlist)과 도구 출력 최대 길이 등 보안 정책을
 * 런타임에 관리합니다. 정책 변경 시 즉시 MCP 매니저에 재적용됩니다.
 *
 * @see McpSecurityPolicyStore
 * @see McpSecurityPolicyProvider
 * @see McpManager
 */
@Tag(name = "MCP Security", description = "Dynamic MCP allowlist and security policy (ADMIN)")
@RestController
@RequestMapping("/api/mcp/security")
class McpSecurityController(
    private val properties: AgentProperties,
    private val store: McpSecurityPolicyStore,
    private val provider: McpSecurityPolicyProvider,
    private val mcpManager: McpManager,
    private val adminAuditStore: AdminAuditStore
) {

    /** 현재 적용 중인 보안 정책과 저장된 정책, 설정 기본값을 조회한다. */
    @Operation(summary = "MCP Security Policy 상태 조회 (적용 중 + 저장 + 기본값) (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Current MCP security policy state"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun get(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(
            McpSecurityPolicyStateResponse(
                effective = provider.currentPolicy().toResponse(),
                stored = store.getOrNull()?.toResponse(),
                configDefault = McpSecurityPolicy(
                    allowedServerNames = properties.mcp.security.allowedServerNames,
                    maxToolOutputLength = properties.mcp.security.maxToolOutputLength
                ).toResponse()
            )
        )
    }

    /** MCP Security Policy를 수정하고 즉시 재적용한다. */
    @Operation(summary = "MCP Security Policy 수정 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "MCP security policy updated"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PutMapping
    fun update(
        @Valid @RequestBody request: UpdateMcpSecurityPolicyRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val saved = store.save(request.toPolicy())
        provider.invalidate()
        mcpManager.reapplySecurityPolicy()
        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_security",
            action = "UPDATE",
            actor = currentActor(exchange),
            resourceType = "mcp_security",
            resourceId = "singleton",
            detail = "allowedServers=${saved.allowedServerNames.size}, maxToolOutputLength=${saved.maxToolOutputLength}"
        )
        return ResponseEntity.ok(saved.toResponse())
    }

    /** 저장된 MCP Security Policy를 삭제하고 설정 기본값으로 복원한다. */
    @Operation(summary = "저장된 MCP Security Policy 삭제 (설정 기본값 복원) (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Policy deleted, reset to config defaults"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @DeleteMapping
    fun delete(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        store.delete()
        provider.invalidate()
        mcpManager.reapplySecurityPolicy()
        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_security",
            action = "DELETE",
            actor = currentActor(exchange),
            resourceType = "mcp_security",
            resourceId = "singleton",
            detail = "reset_to_config_defaults=true"
        )
        return ResponseEntity.noContent().build()
    }
}

data class McpSecurityPolicyStateResponse(
    val effective: McpSecurityPolicyResponse,
    val stored: McpSecurityPolicyResponse?,
    val configDefault: McpSecurityPolicyResponse
)

data class McpSecurityPolicyResponse(
    val allowedServerNames: Set<String>,
    val maxToolOutputLength: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class UpdateMcpSecurityPolicyRequest(
    @field:Size(max = 500, message = "allowedServerNames must not exceed 500 entries")
    val allowedServerNames: Set<String> = emptySet(),
    @field:Min(value = 1024, message = "maxToolOutputLength must be at least 1024")
    @field:Max(value = 500000, message = "maxToolOutputLength must not exceed 500000")
    val maxToolOutputLength: Int = 50_000
) {
    fun toPolicy(): McpSecurityPolicy = McpSecurityPolicy(
        allowedServerNames = allowedServerNames
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet(),
        maxToolOutputLength = maxToolOutputLength
    )
}

private fun McpSecurityPolicy.toResponse(): McpSecurityPolicyResponse = McpSecurityPolicyResponse(
    allowedServerNames = allowedServerNames,
    maxToolOutputLength = maxToolOutputLength,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)
