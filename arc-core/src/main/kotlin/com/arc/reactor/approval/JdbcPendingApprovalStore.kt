package com.arc.reactor.approval

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * JDBC 기반 대기 승인 저장소
 *
 * DB 행을 진실의 원천(source of truth)으로 사용하여
 * 승인 API와 에이전트 대기자가 메모리 공유 상태 없이 조율할 수 있다.
 * 다중 인스턴스 배포에 적합하다.
 *
 * ## 폴링 방식
 * [InMemoryPendingApprovalStore]와 달리 CompletableDeferred를 사용할 수 없으므로
 * DB를 주기적으로 폴링하여 승인/거부 상태 변경을 감지한다.
 *
 * ## 정리 정책
 * 해결된(승인/거부/타임아웃) 행은 [resolvedRetentionMs] 이후 자동 삭제된다.
 *
 * @param jdbcTemplate Spring JdbcTemplate
 * @param defaultTimeoutMs 기본 승인 타임아웃 (밀리초, 기본값: 5분)
 * @param pollIntervalMs DB 폴링 간격 (밀리초, 기본값: 250ms)
 * @param resolvedRetentionMs 해결된 행 보관 기간 (밀리초, 기본값: 7일)
 *
 * @see PendingApprovalStore 저장소 인터페이스
 * @see InMemoryPendingApprovalStore 메모리 기반 대안
 */
class JdbcPendingApprovalStore(
    private val jdbcTemplate: JdbcTemplate,
    private val defaultTimeoutMs: Long = 300_000,
    private val pollIntervalMs: Long = 250,
    private val resolvedRetentionMs: Long = 7 * 24 * 60 * 60 * 1000L
) : PendingApprovalStore {

    /** 마지막 cleanup 실행 시각 (epoch millis) */
    private val lastCleanupMs = AtomicLong(0L)

    override suspend fun requestApproval(
        runId: String,
        userId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long,
        context: ApprovalContext?
    ): ToolApprovalResponse {
        val id = UUID.randomUUID().toString()
        val requestedAt = Instant.now()
        val effectiveTimeoutMs = if (timeoutMs > 0) timeoutMs else defaultTimeoutMs

        // ── 단계 1: 정리 + PENDING 행 삽입 ──
        withContext(Dispatchers.IO) {
            cleanupIfNeeded()
            jdbcTemplate.update(
                """
                INSERT INTO pending_approvals
                (id, run_id, user_id, tool_name, arguments, timeout_ms, status, requested_at, context_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, runId, userId, toolName,
                objectMapper.writeValueAsString(arguments),
                effectiveTimeoutMs, ApprovalStatus.PENDING.name,
                Timestamp.from(requestedAt),
                context?.let { serializeContext(it) }
            )
        }

        // ── 단계 2: 상태 변경을 폴링하며 대기 ──
        val deadline = System.currentTimeMillis() + effectiveTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = withContext(Dispatchers.IO) { findState(id) }
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

        // ── 단계 3: 타임아웃 처리 ──
        withContext(Dispatchers.IO) {
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
                id, ApprovalStatus.PENDING.name
            )
        }

        return ToolApprovalResponse(
            approved = false,
            reason = "Approval timed out after ${effectiveTimeoutMs}ms"
        )
    }

    override fun listPending(): List<ApprovalSummary> {
        cleanupIfNeeded()

        return jdbcTemplate.query(
            """
            SELECT id, run_id, user_id, tool_name, arguments, requested_at, context_json
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
                    status = ApprovalStatus.PENDING,
                    context = parseNullableContext(rs.getString("context_json"))
                )
            },
            ApprovalStatus.PENDING.name
        )
    }

    override fun listPendingByUser(userId: String): List<ApprovalSummary> {
        cleanupIfNeeded()

        return jdbcTemplate.query(
            """
            SELECT id, run_id, user_id, tool_name, arguments, requested_at, context_json
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
                    status = ApprovalStatus.PENDING,
                    context = parseNullableContext(rs.getString("context_json"))
                )
            },
            ApprovalStatus.PENDING.name,
            userId
        )
    }

    override fun approve(approvalId: String, modifiedArguments: Map<String, Any?>?): Boolean {
        cleanupIfNeeded()

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
        cleanupIfNeeded()

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

    /** 마지막 cleanup 후 [CLEANUP_INTERVAL_MS] 경과 시에만 정리를 실행한다 */
    private fun cleanupIfNeeded() {
        val now = System.currentTimeMillis()
        val last = lastCleanupMs.get()
        if (now - last < CLEANUP_INTERVAL_MS) return
        if (!lastCleanupMs.compareAndSet(last, now)) return
        cleanupResolvedRows()
    }

    /** 보관 기간이 지난 해결된 행을 삭제한다 */
    private fun cleanupResolvedRows() {
        val retentionMs = resolvedRetentionMs.coerceAtLeast(0)
        val cutoff = Instant.now().minusMillis(retentionMs)
        jdbcTemplate.update(
            """
            DELETE FROM pending_approvals
             WHERE status IN (?, ?, ?)
               AND resolved_at IS NOT NULL
               AND resolved_at < ?
            """.trimIndent(),
            ApprovalStatus.APPROVED.name,
            ApprovalStatus.REJECTED.name,
            ApprovalStatus.TIMED_OUT.name,
            Timestamp.from(cutoff)
        )
    }

    /** ID로 승인 상태를 조회한다 */
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

    /** JSON 문자열을 Map으로 파싱한다 (실패 시 빈 맵 반환) */
    private fun parseJsonMap(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { objectMapper.readValue<Map<String, Any?>>(json) }
            .getOrDefault(emptyMap())
    }

    /** JSON 문자열을 nullable Map으로 파싱한다 (실패 시 null 반환) */
    private fun parseNullableJsonMap(json: String?): Map<String, Any?>? {
        if (json.isNullOrBlank()) return null
        return runCatching { objectMapper.readValue<Map<String, Any?>>(json) }
            .getOrNull()
    }

    /** [ApprovalContext]를 JSON 문자열로 직렬화한다. 정보가 없으면 null 반환. */
    private fun serializeContext(context: ApprovalContext): String? {
        if (!context.hasAnyInformation()) return null
        return runCatching { objectMapper.writeValueAsString(context) }.getOrNull()
    }

    /**
     * JSON 문자열을 [ApprovalContext]로 역직렬화한다.
     * 빈 문자열/파싱 실패 시 null을 반환하여 기존 row(context_json=NULL)와 호환한다.
     */
    private fun parseNullableContext(json: String?): ApprovalContext? {
        if (json.isNullOrBlank()) return null
        return runCatching { objectMapper.readValue<ApprovalContext>(json) }.getOrNull()
    }

    /** 내부 상태 조회용 데이터 클래스 */
    private data class ApprovalState(
        val status: ApprovalStatus,
        val reason: String?,
        val modifiedArguments: Map<String, Any?>?
    )

    companion object {
        /** cleanup 실행 최소 간격 (5분) */
        private const val CLEANUP_INTERVAL_MS = 300_000L
        private val objectMapper = jacksonObjectMapper()
    }
}
