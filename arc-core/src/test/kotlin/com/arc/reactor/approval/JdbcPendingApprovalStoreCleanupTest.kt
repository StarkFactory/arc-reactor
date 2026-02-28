package com.arc.reactor.approval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import java.sql.Timestamp
import java.time.Instant

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
                resolved_at        TIMESTAMP
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
    fun `listPending cleans up old resolved rows by retention`() {
        val oldResolvedAt = Instant.now().minusSeconds(7_200)
        val freshResolvedAt = Instant.now()

        insertResolved(id = "old-approved", status = ApprovalStatus.APPROVED, resolvedAt = oldResolvedAt)
        insertResolved(id = "fresh-approved", status = ApprovalStatus.APPROVED, resolvedAt = freshResolvedAt)

        val pending = store.listPending()

        assertTrue(pending.isEmpty(), "listPending should return empty after cleanup removes expired resolved records")
        val ids = jdbcTemplate.queryForList("SELECT id FROM pending_approvals ORDER BY id", String::class.java)
        assertEquals(listOf("fresh-approved"), ids)
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
}
