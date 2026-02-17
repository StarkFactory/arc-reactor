package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@Tag(name = "Admin Audits", description = "Unified admin audit logs (ADMIN)")
@RestController
@RequestMapping("/api/admin/audits")
class AdminAuditController(
    private val store: AdminAuditStore
) {

    @Operation(summary = "List admin audit logs (ADMIN)")
    @GetMapping
    fun list(
        @RequestParam(required = false) @Min(1) @Max(1000) limit: Int?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) action: String?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val size = (limit ?: 100).coerceIn(1, 1000)
        val rows = store.list(limit = size, category = category, action = action).map {
            AdminAuditResponse(
                id = it.id,
                category = it.category,
                action = it.action,
                actor = it.actor,
                resourceType = it.resourceType,
                resourceId = it.resourceId,
                detail = it.detail,
                createdAt = it.createdAt.toEpochMilli()
            )
        }
        return ResponseEntity.ok(rows)
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
