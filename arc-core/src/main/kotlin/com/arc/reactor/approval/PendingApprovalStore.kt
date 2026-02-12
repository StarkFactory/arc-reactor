package com.arc.reactor.approval

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Store for managing pending tool approval requests.
 *
 * Uses [CompletableDeferred] to suspend the agent coroutine until
 * a human approves or rejects the tool call via the REST API.
 *
 * ## Flow
 * 1. Agent hits a tool call that requires approval
 * 2. [requestApproval] creates a pending entry and suspends
 * 3. Human calls REST API → [approve] or [reject]
 * 4. CompletableDeferred completes, agent resumes
 */
interface PendingApprovalStore {

    /**
     * Submit an approval request and suspend until resolved.
     *
     * @param runId Agent run ID
     * @param userId User who initiated the request
     * @param toolName Tool requiring approval
     * @param arguments Tool call arguments
     * @param timeoutMs Maximum wait time (0 = use default)
     * @return Approval response from the human
     */
    suspend fun requestApproval(
        runId: String,
        userId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long = 0
    ): ToolApprovalResponse

    /**
     * List all pending approvals.
     */
    fun listPending(): List<ApprovalSummary>

    /**
     * List pending approvals for a specific user.
     */
    fun listPendingByUser(userId: String): List<ApprovalSummary>

    /**
     * Approve a pending request.
     *
     * @param approvalId The approval request ID
     * @param modifiedArguments Optional modified arguments
     * @return true if the approval was found and processed
     */
    fun approve(approvalId: String, modifiedArguments: Map<String, Any?>? = null): Boolean

    /**
     * Reject a pending request.
     *
     * @param approvalId The approval request ID
     * @param reason Optional rejection reason
     * @return true if the rejection was found and processed
     */
    fun reject(approvalId: String, reason: String? = null): Boolean
}

/**
 * In-memory implementation of [PendingApprovalStore].
 * Suitable for single-instance deployments.
 */
class InMemoryPendingApprovalStore(
    private val defaultTimeoutMs: Long = 300_000 // 5 minutes
) : PendingApprovalStore {

    private data class PendingEntry(
        val request: ToolApprovalRequest,
        val deferred: CompletableDeferred<ToolApprovalResponse>
    )

    private val pending = ConcurrentHashMap<String, PendingEntry>()

    override suspend fun requestApproval(
        runId: String,
        userId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long
    ): ToolApprovalResponse {
        val id = UUID.randomUUID().toString()
        val request = ToolApprovalRequest(
            id = id, runId = runId, userId = userId,
            toolName = toolName, arguments = arguments,
            timeoutMs = timeoutMs
        )
        val deferred = CompletableDeferred<ToolApprovalResponse>()
        pending[id] = PendingEntry(request, deferred)

        logger.info { "Approval requested: id=$id, tool=$toolName, user=$userId" }

        val effectiveTimeout = if (timeoutMs > 0) timeoutMs else defaultTimeoutMs

        return try {
            val response = withTimeoutOrNull(effectiveTimeout) { deferred.await() }
            if (response != null) {
                response
            } else {
                logger.warn { "Approval timed out: id=$id, tool=$toolName" }
                ToolApprovalResponse(approved = false, reason = "Approval timed out after ${effectiveTimeout}ms")
            }
        } finally {
            pending.remove(id)
        }
    }

    override fun listPending(): List<ApprovalSummary> {
        return pending.values.map { it.toSummary() }
    }

    override fun listPendingByUser(userId: String): List<ApprovalSummary> {
        return pending.values
            .filter { it.request.userId == userId }
            .map { it.toSummary() }
    }

    override fun approve(approvalId: String, modifiedArguments: Map<String, Any?>?): Boolean {
        val entry = pending[approvalId] ?: return false
        val response = ToolApprovalResponse(
            approved = true,
            modifiedArguments = modifiedArguments
        )
        logger.info { "Approval granted: id=$approvalId, tool=${entry.request.toolName}" }
        return entry.deferred.complete(response)
    }

    override fun reject(approvalId: String, reason: String?): Boolean {
        val entry = pending[approvalId] ?: return false
        val response = ToolApprovalResponse(
            approved = false,
            reason = reason ?: "Rejected by human"
        )
        logger.info { "Approval rejected: id=$approvalId, tool=${entry.request.toolName}, reason=$reason" }
        return entry.deferred.complete(response)
    }

    private fun PendingEntry.toSummary() = ApprovalSummary(
        id = request.id,
        runId = request.runId,
        userId = request.userId,
        toolName = request.toolName,
        arguments = request.arguments,
        requestedAt = request.requestedAt,
        status = ApprovalStatus.PENDING
    )
}
