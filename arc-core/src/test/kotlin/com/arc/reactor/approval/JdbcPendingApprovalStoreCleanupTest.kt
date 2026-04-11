package com.arc.reactor.approval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import java.sql.Timestamp
import java.time.Instant

@Tag("safety")
/**
 * JDBC PendingApprovalStore 정리에 대한 테스트.
 *
 * JDBC 기반 대기 중 승인 저장소의 정리 로직을 검증합니다.
 */
class JdbcPendingApprovalStoreCleanupTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcPendingApprovalStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()
        jdbcTemplate = JdbcTemplate(dataSource)

        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS pending_approvals (
                id                 VARCHAR(36)   PRIMARY KEY,
                run_id             VARCHAR(120)  NOT NULL,
                user_id            VARCHAR(120)  NOT NULL,
                tool_name          VARCHAR(200)  NOT NULL,
                arguments          TEXT          NOT NULL,
                timeout_ms         BIGINT        NOT NULL DEFAULT 300000,
                status             VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
                reason             TEXT,
                modified_arguments TEXT,
                requested_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                resolved_at        TIMESTAMP,
                context_json       TEXT
            )
            """.trimIndent()
        )

        store = JdbcPendingApprovalStore(
            jdbcTemplate = jdbcTemplate,
            defaultTimeoutMs = 3_000,
            pollIntervalMs = 10,
            resolvedRetentionMs = 60_000
        )
    }

    @Test
    fun `listPending은(는) cleans up old resolved rows by retention`() {
        val oldResolvedAt = Instant.now().minusSeconds(7_200)
        val freshResolvedAt = Instant.now()

        insertResolved(id = "old-approved", status = ApprovalStatus.APPROVED, resolvedAt = oldResolvedAt)
        insertResolved(id = "fresh-approved", status = ApprovalStatus.APPROVED, resolvedAt = freshResolvedAt)

        val pending = store.listPending()

        assertTrue(pending.isEmpty(), "listPending should return empty after cleanup removes expired resolved records")
        val ids = jdbcTemplate.queryForList("SELECT id FROM pending_approvals ORDER BY id", String::class.java)
        assertEquals(listOf("fresh-approved"), ids)
    }

    /**
     * R338 regression: resolved 상태(`TIMED_OUT` 등)인데 `resolved_at`이 NULL인 행도
     * `requested_at` fallback 기준으로 회수되어야 한다. 이전 cleanup 쿼리는
     * `AND resolved_at IS NOT NULL`만 가지고 있어 이런 행이 영구 잔류했다.
     *
     * 시나리오:
     * - `old-timeout-null`: TIMED_OUT 상태 + resolved_at=NULL + requested_at=2시간 전
     *   → retention(60초) 초과 → R338 fix 적용 후 삭제되어야 한다
     * - `fresh-timeout-null`: TIMED_OUT 상태 + resolved_at=NULL + requested_at=지금
     *   → retention 내 → 유지되어야 한다
     * - `old-timeout-resolved`: TIMED_OUT 상태 + resolved_at=2시간 전 (정상 경로)
     *   → 기존 로직으로도 삭제되어야 한다 (회귀 검증)
     */
    @Test
    fun `R338 resolved_at NULL인 TIMED_OUT 행도 requested_at 기준으로 cleanup 되어야 한다`() {
        val oldRequestedAt = Instant.now().minusSeconds(7_200)
        val freshRequestedAt = Instant.now()
        val oldResolvedAt = Instant.now().minusSeconds(7_200)

        // (1) 오래된 TIMED_OUT + resolved_at NULL — R338 fix가 requested_at fallback으로 회수
        insertTimedOutNull(id = "old-timeout-null", requestedAt = oldRequestedAt)
        // (2) 신선한 TIMED_OUT + resolved_at NULL — retention 내이므로 유지되어야 한다
        insertTimedOutNull(id = "fresh-timeout-null", requestedAt = freshRequestedAt)
        // (3) 오래된 TIMED_OUT + resolved_at 세팅 (정상 경로) — 기존 로직으로도 삭제
        insertResolved(id = "old-timeout-resolved", status = ApprovalStatus.TIMED_OUT, resolvedAt = oldResolvedAt)

        // cleanup 트리거 — listPending 호출 시 cleanupIfNeeded 실행
        store.listPending()

        val remainingIds = jdbcTemplate.queryForList(
            "SELECT id FROM pending_approvals ORDER BY id",
            String::class.java
        )
        assertEquals(listOf("fresh-timeout-null"), remainingIds) {
            "R338: resolved_at이 NULL이어도 requested_at 기준으로 retention 초과 행이 삭제되어야 한다. " +
                "실제 잔류 id=$remainingIds"
        }
    }

    private fun insertResolved(id: String, status: ApprovalStatus, resolvedAt: Instant) {
        jdbcTemplate.update(
            """
            INSERT INTO pending_approvals
            (id, run_id, user_id, tool_name, arguments, timeout_ms, status, requested_at, resolved_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            "run-1",
            "user-1",
            "tool-a",
            "{}",
            10_000L,
            status.name,
            Timestamp.from(Instant.now().minusSeconds(7_200)),
            Timestamp.from(resolvedAt)
        )
    }

    /**
     * R338 테스트 헬퍼: `TIMED_OUT` 상태이면서 `resolved_at`이 NULL인 "병적" 행을 주입한다.
     * 실제 운영에서는 다중 인스턴스 race, legacy migration, 외부 스크립트 조작 등으로 발생할 수 있다.
     */
    private fun insertTimedOutNull(id: String, requestedAt: Instant) {
        jdbcTemplate.update(
            """
            INSERT INTO pending_approvals
            (id, run_id, user_id, tool_name, arguments, timeout_ms, status, requested_at, resolved_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
            """.trimIndent(),
            id,
            "run-1",
            "user-1",
            "tool-a",
            "{}",
            10_000L,
            ApprovalStatus.TIMED_OUT.name,
            Timestamp.from(requestedAt)
        )
    }
}
