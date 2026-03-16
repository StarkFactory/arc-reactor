package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.auth.AdminAuthorizationSupport.maskedAdminAccountRef
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * 관리자 감사 로그 컨트롤러.
 *
 * 모든 관리자 작업(MCP 서버, 승인, 보안 정책 등)의 통합 감사 로그를 조회합니다.
 * 카테고리 및 액션으로 필터링하고 페이지네이션을 지원합니다.
 *
 * @see AdminAuditStore
 */
@Tag(name = "Admin Audits", description = "Unified admin audit logs (ADMIN)")
@RestController
@RequestMapping("/api/admin/audits")
class AdminAuditController(
    private val store: AdminAuditStore
) {

    /**
     * 관리자 감사 로그 목록을 조회한다.
     * WHY: actor 정보는 보안을 위해 마스킹하여 반환한다.
     */
    @Operation(summary = "관리자 감사 로그 목록 조회 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Paginated list of admin audit logs"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun list(
        @RequestParam(required = false) @Min(1) @Max(1000) limit: Int?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") pageLimit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val size = (limit ?: 1000).coerceIn(1, 1000)
        val clampedPageLimit = clampLimit(pageLimit)
        val rows = store.list(limit = size, category = category, action = action).map {
            AdminAuditResponse(
                id = it.id,
                category = it.category,
                action = it.action,
                actor = maskedAdminAccountRef(it.actor),
                resourceType = it.resourceType,
                resourceId = it.resourceId,
                detail = it.detail,
                createdAt = it.createdAt.toEpochMilli()
            )
        }
        return ResponseEntity.ok(rows.paginate(offset, clampedPageLimit))
    }
}

data class AdminAuditResponse(
    val id: String,
    val category: String,
    val action: String,
    val actor: String,
    val resourceType: String?,
    val resourceId: String?,
    val detail: String?,
    val createdAt: Long
)
