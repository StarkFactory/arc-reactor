package com.arc.reactor.controller

import com.arc.reactor.approval.ApprovalSummary
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.auth.JwtAuthWebFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * Human-in-the-Loop Approval API
 *
 * Provides REST endpoints for managing pending tool approval requests.
 * Only registered when a [PendingApprovalStore] bean is available
 * (i.e., when HITL is enabled via configuration).
 *
 * ## Endpoints
 * - GET /api/approvals          : List pending approvals (filtered by user)
 * - POST /api/approvals/{id}/approve : Approve a tool call
 * - POST /api/approvals/{id}/reject  : Reject a tool call
 */
@Tag(name = "Approvals", description = "Human-in-the-Loop tool approval endpoints")
@RestController
@RequestMapping("/api/approvals")
@ConditionalOnBean(PendingApprovalStore::class)
class ApprovalController(
    private val pendingApprovalStore: PendingApprovalStore
) {

    @Operation(summary = "List pending approval requests")
    @GetMapping
    fun listPending(exchange: ServerWebExchange): List<ApprovalSummary> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
        return if (userId != null) {
            pendingApprovalStore.listPendingByUser(userId)
        } else {
            pendingApprovalStore.listPending()
        }
    }

    @Operation(summary = "Approve a pending tool call")
    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: String,
        @RequestBody(required = false) request: ApproveRequest?
    ): ApprovalActionResponse {
        val success = pendingApprovalStore.approve(id, request?.modifiedArguments)
        return ApprovalActionResponse(
            success = success,
            message = if (success) "Approved" else "Approval not found or already resolved"
        )
    }

    @Operation(summary = "Reject a pending tool call")
    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: String,
        @RequestBody(required = false) request: RejectRequest?
    ): ApprovalActionResponse {
        val success = pendingApprovalStore.reject(id, request?.reason)
        return ApprovalActionResponse(
            success = success,
            message = if (success) "Rejected" else "Approval not found or already resolved"
        )
    }
}

data class ApproveRequest(
    val modifiedArguments: Map<String, Any?>? = null
)

data class RejectRequest(
    val reason: String? = null
)

data class ApprovalActionResponse(
    val success: Boolean,
    val message: String
)
