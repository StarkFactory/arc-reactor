package com.arc.reactor.guard.output.policy

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

data class OutputGuardRuleAuditLog(
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String? = null,
    val action: OutputGuardRuleAuditAction,
    val actor: String,
    val detail: String? = null,
    val createdAt: Instant = Instant.now()
)

enum class OutputGuardRuleAuditAction {
    CREATE,
    UPDATE,
    DELETE,
    SIMULATE
}

interface OutputGuardRuleAuditStore {
    fun list(limit: Int = 100): List<OutputGuardRuleAuditLog>
    fun save(log: OutputGuardRuleAuditLog): OutputGuardRuleAuditLog
}

class InMemoryOutputGuardRuleAuditStore : OutputGuardRuleAuditStore {
    private val logs = ConcurrentLinkedDeque<OutputGuardRuleAuditLog>()

    override fun list(limit: Int): List<OutputGuardRuleAuditLog> {
        val size = limit.coerceIn(1, 1000)
        return logs.asSequence()
            .take(size)
            .toList()
    }

    override fun save(log: OutputGuardRuleAuditLog): OutputGuardRuleAuditLog {
        logs.addFirst(log)
        while (logs.size > 5000) {
            logs.pollLast()
        }
        return log
    }
}

class JdbcOutputGuardRuleAuditStore(
    private val jdbcTemplate: JdbcTemplate
) : OutputGuardRuleAuditStore {

    override fun list(limit: Int): List<OutputGuardRuleAuditLog> {
        return jdbcTemplate.query(
            """SELECT id, rule_id, action, actor, detail, created_at
               FROM output_guard_rule_audits
               ORDER BY created_at DESC
               LIMIT ?""",
            ROW_MAPPER,
            limit.coerceIn(1, 1000)
        )
    }

    override fun save(log: OutputGuardRuleAuditLog): OutputGuardRuleAuditLog {
        jdbcTemplate.update(
            """INSERT INTO output_guard_rule_audits (id, rule_id, action, actor, detail, created_at)
               VALUES (?, ?, ?, ?, ?, ?)""",
            log.id,
            log.ruleId,
            log.action.name,
            log.actor,
            log.detail,
            java.sql.Timestamp.from(log.createdAt)
        )
        return log
    }

    companion object {
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            OutputGuardRuleAuditLog(
                id = rs.getString("id"),
                ruleId = rs.getString("rule_id"),
                action = OutputGuardRuleAuditAction.valueOf(rs.getString("action")),
                actor = rs.getString("actor"),
                detail = rs.getString("detail"),
                createdAt = rs.getTimestamp("created_at").toInstant()
            )
        }
    }
}
