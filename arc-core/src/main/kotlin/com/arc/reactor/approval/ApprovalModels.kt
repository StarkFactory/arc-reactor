package com.arc.reactor.approval

import java.time.Instant

/**
 * A pending tool approval request.
 */
data class ToolApprovalRequest(
    /** Unique approval request ID */
    val id: String,

    /** Agent run ID (correlates with HookContext.runId) */
    val runId: String,

    /** User who initiated the agent request */
    val userId: String,

    /** Tool name that requires approval */
    val toolName: String,

    /** Tool arguments from the LLM */
    val arguments: Map<String, Any?>,

    /** Timestamp when approval was requested */
    val requestedAt: Instant = Instant.now(),

    /** Timeout in milliseconds for approval (0 = no timeout) */
    val timeoutMs: Long = 0
)

/**
 * Human's response to an approval request.
 */
data class ToolApprovalResponse(
    /** Whether the tool call is approved */
    val approved: Boolean,

    /** Optional reason for rejection */
    val reason: String? = null,

    /** Optional modified arguments (allows human to adjust tool parameters) */
    val modifiedArguments: Map<String, Any?>? = null
)

/**
 * Status of a pending approval.
 */
enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    TIMED_OUT
}

/**
 * Summary of a pending approval for the REST API.
 */
data class ApprovalSummary(
    val id: String,
    val runId: String,
    val userId: String,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val requestedAt: Instant,
    val status: ApprovalStatus
)
