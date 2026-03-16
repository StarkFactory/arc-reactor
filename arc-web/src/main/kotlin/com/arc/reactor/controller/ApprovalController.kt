package com.arc.reactor.controller

import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import io.swagger.v3.oas.annotations.Operation
import mu.KotlinLogging
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

private val logger = KotlinLogging.logger {}

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
@ConditionalOnProperty(
    prefix = "arc.reactor.approval", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class ApprovalController(
    private val pendingApprovalStore: PendingApprovalStore,
    private val adminAuditStore: AdminAuditStore
) {

    @Operation(summary = "List pending approval requests")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Paginated list of pending approvals"),
        ApiResponse(responseCode = "403", description = "Access denied")
    ])
    @GetMapping
    fun listPending(
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
        val pending = when {
            isAdmin(exchange) -> pendingApprovalStore.listPending()
            userId != null -> pendingApprovalStore.listPendingByUser(userId)
            else -> return forbiddenResponse()
        }
        val clamped = clampLimit(limit)
        return ResponseEntity.ok(pending.map { it.toAdminResponse() }.paginate(offset, clamped))
    }

    @Operation(summary = "Approve a pending tool call")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Approval action result"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: String,
        @RequestBody(required = false) request: ApproveRequest?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) {
            return forbiddenResponse()
        }
        val actor = currentActor(exchange)
        val modifiedArgs = request?.modifiedArguments
        val detail = modifiedArgs?.let { "modifiedArguments=$it" }
        logger.info {
            "audit category=approval action=APPROVE actor=$actor resourceId=$id modifiedArgs=${modifiedArgs != null}"
        }
        recordAdminAudit(
            store = adminAuditStore,
            category = "approval",
            action = "APPROVE",
            actor = actor,
            resourceType = "approval",
            resourceId = id,
            detail = detail
        )
        val success = pendingApprovalStore.approve(id, request?.modifiedArguments)
        return ResponseEntity.ok(
            ApprovalActionResponse(
                success = success,
                message = if (success) "Approved" else "Approval not found or already resolved"
            )
        )
    }

    @Operation(summary = "Reject a pending tool call")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Rejection action result"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: String,
        @RequestBody(required = false) request: RejectRequest?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) {
            return forbiddenResponse()
        }
        val actor = currentActor(exchange)
        val detail = request?.reason?.let { "reason=$it" }
        logger.info {
            "audit category=approval action=REJECT actor=$actor resourceId=$id reason=${request?.reason}"
        }
        recordAdminAudit(
            store = adminAuditStore,
            category = "approval",
            action = "REJECT",
            actor = actor,
            resourceType = "approval",
            resourceId = id,
            detail = detail
        )
        val success = pendingApprovalStore.reject(id, request?.reason)
        return ResponseEntity.ok(
            ApprovalActionResponse(
                success = success,
                message = if (success) "Rejected" else "Approval not found or already resolved"
            )
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

data class AdminApprovalSummaryResponse(
    val id: String,
    val runId: String,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val requestedAt: String,
    val status: String
)

private fun com.arc.reactor.approval.ApprovalSummary.toAdminResponse(): AdminApprovalSummaryResponse {
    return AdminApprovalSummaryResponse(
        id = id,
        runId = runId,
        toolName = toolName,
        arguments = arguments,
        requestedAt = requestedAt.toString(),
        status = status.name
    )
}
