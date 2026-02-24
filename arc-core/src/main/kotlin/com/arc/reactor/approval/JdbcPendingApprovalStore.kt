package com.arc.reactor.approval

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * JDBC-backed [PendingApprovalStore].
 *
 * Uses DB rows as the source of truth so approval APIs and agent waiters
 * can coordinate without in-memory shared state.
 */
class JdbcPendingApprovalStore(
    private val jdbcTemplate: JdbcTemplate,
    private val defaultTimeoutMs: Long = 300_000,
    private val pollIntervalMs: Long = 250
) : PendingApprovalStore {

    override suspend fun requestApproval(
        runId: String,
        userId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long
    ): ToolApprovalResponse {
        val id = UUID.randomUUID().toString()
        val requestedAt = Instant.now()
        val effectiveTimeoutMs = if (timeoutMs > 0) timeoutMs else defaultTimeoutMs

        jdbcTemplate.update(
            """
            INSERT INTO pending_approvals
            (id, run_id, user_id, tool_name, arguments, timeout_ms, status, requested_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            runId,
            userId,
            toolName,
            objectMapper.writeValueAsString(arguments),
            effectiveTimeoutMs,
            ApprovalStatus.PENDING.name,
            Timestamp.from(requestedAt)
        )

        val deadline = System.currentTimeMillis() + effectiveTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = findState(id)
            if (state == null) {
                return ToolApprovalResponse(
                    approved = false,
                    reason = "Approval request not found"
                )
            }

            when (state.status) {
                ApprovalStatus.PENDING -> delay(pollIntervalMs)
                ApprovalStatus.APPROVED -> {
                    return ToolApprovalResponse(
                        approved = true,
                        modifiedArguments = state.modifiedArguments
                    )
                }
                ApprovalStatus.REJECTED -> {
                    return ToolApprovalResponse(
                        approved = false,
                        reason = state.reason ?: "Rejected by human"
                    )
                }
                ApprovalStatus.TIMED_OUT -> {
                    return ToolApprovalResponse(
                        approved = false,
                        reason = state.reason ?: "Approval timed out"
                    )
                }
            }
        }

        val now = Instant.now()
        jdbcTemplate.update(
            """
            UPDATE pending_approvals
               SET status = ?, reason = ?, resolved_at = ?
             WHERE id = ? AND status = ?
            """.trimIndent(),
            ApprovalStatus.TIMED_OUT.name,
            "Approval timed out after ${effectiveTimeoutMs}ms",
            Timestamp.from(now),
            id,
            ApprovalStatus.PENDING.name
        )

        return ToolApprovalResponse(
            approved = false,
            reason = "Approval timed out after ${effectiveTimeoutMs}ms"
        )
    }

    override fun listPending(): List<ApprovalSummary> {
        return jdbcTemplate.query(
            """
            SELECT id, run_id, user_id, tool_name, arguments, requested_at
              FROM pending_approvals
             WHERE status = ?
             ORDER BY requested_at DESC
            """.trimIndent(),
            { rs: ResultSet, _: Int ->
                ApprovalSummary(
                    id = rs.getString("id"),
                    runId = rs.getString("run_id"),
                    userId = rs.getString("user_id"),
                    toolName = rs.getString("tool_name"),
                    arguments = parseJsonMap(rs.getString("arguments")),
                    requestedAt = rs.getTimestamp("requested_at").toInstant(),
                    status = ApprovalStatus.PENDING
                )
            },
            ApprovalStatus.PENDING.name
        )
    }

    override fun listPendingByUser(userId: String): List<ApprovalSummary> {
        return jdbcTemplate.query(
            """
            SELECT id, run_id, user_id, tool_name, arguments, requested_at
              FROM pending_approvals
             WHERE status = ? AND user_id = ?
             ORDER BY requested_at DESC
            """.trimIndent(),
            { rs: ResultSet, _: Int ->
                ApprovalSummary(
                    id = rs.getString("id"),
                    runId = rs.getString("run_id"),
                    userId = rs.getString("user_id"),
                    toolName = rs.getString("tool_name"),
                    arguments = parseJsonMap(rs.getString("arguments")),
                    requestedAt = rs.getTimestamp("requested_at").toInstant(),
                    status = ApprovalStatus.PENDING
                )
            },
            ApprovalStatus.PENDING.name,
            userId
        )
    }

    override fun approve(approvalId: String, modifiedArguments: Map<String, Any?>?): Boolean {
        val now = Instant.now()
        val updated = jdbcTemplate.update(
            """
            UPDATE pending_approvals
               SET status = ?, modified_arguments = ?, reason = NULL, resolved_at = ?
             WHERE id = ? AND status = ?
            """.trimIndent(),
            ApprovalStatus.APPROVED.name,
            modifiedArguments?.let { objectMapper.writeValueAsString(it) },
            Timestamp.from(now),
            approvalId,
            ApprovalStatus.PENDING.name
        )
        return updated > 0
    }

    override fun reject(approvalId: String, reason: String?): Boolean {
        val now = Instant.now()
        val updated = jdbcTemplate.update(
            """
            UPDATE pending_approvals
               SET status = ?, reason = ?, modified_arguments = NULL, resolved_at = ?
             WHERE id = ? AND status = ?
            """.trimIndent(),
            ApprovalStatus.REJECTED.name,
            reason ?: "Rejected by human",
            Timestamp.from(now),
            approvalId,
            ApprovalStatus.PENDING.name
        )
        return updated > 0
    }

    private fun findState(id: String): ApprovalState? {
        return jdbcTemplate.query(
            """
            SELECT status, reason, modified_arguments
              FROM pending_approvals
             WHERE id = ?
            """.trimIndent(),
            { rs: ResultSet, _: Int ->
                ApprovalState(
                    status = runCatching { ApprovalStatus.valueOf(rs.getString("status")) }
                        .getOrDefault(ApprovalStatus.PENDING),
                    reason = rs.getString("reason"),
                    modifiedArguments = parseNullableJsonMap(rs.getString("modified_arguments"))
                )
            },
            id
        ).firstOrNull()
    }

    private fun parseJsonMap(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { objectMapper.readValue<Map<String, Any?>>(json) }
            .getOrDefault(emptyMap())
    }

    private fun parseNullableJsonMap(json: String?): Map<String, Any?>? {
        if (json.isNullOrBlank()) return null
        return runCatching { objectMapper.readValue<Map<String, Any?>>(json) }
            .getOrNull()
    }

    private data class ApprovalState(
        val status: ApprovalStatus,
        val reason: String?,
        val modifiedArguments: Map<String, Any?>?
    )

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
