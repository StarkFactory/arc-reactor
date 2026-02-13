package com.arc.reactor.guard.output.policy

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

/**
 * JDBC-backed store for dynamic output guard rules.
 */
class JdbcOutputGuardRuleStore(
    private val jdbcTemplate: JdbcTemplate
) : OutputGuardRuleStore {

    override fun list(): List<OutputGuardRule> {
        return jdbcTemplate.query(
            """SELECT id, name, pattern, action, priority, enabled, created_at, updated_at
               FROM output_guard_rules
               ORDER BY priority ASC, created_at ASC""",
            ROW_MAPPER
        )
    }

    override fun findById(id: String): OutputGuardRule? {
        val results = jdbcTemplate.query(
            """SELECT id, name, pattern, action, priority, enabled, created_at, updated_at
               FROM output_guard_rules
               WHERE id = ?""",
            ROW_MAPPER,
            id
        )
        return results.firstOrNull()
    }

    override fun save(rule: OutputGuardRule): OutputGuardRule {
        jdbcTemplate.update(
            """INSERT INTO output_guard_rules (id, name, pattern, action, priority, enabled, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            rule.id,
            rule.name,
            rule.pattern,
            rule.action.name,
            rule.priority,
            rule.enabled,
            java.sql.Timestamp.from(rule.createdAt),
            java.sql.Timestamp.from(rule.updatedAt)
        )
        return rule
    }

    override fun update(id: String, rule: OutputGuardRule): OutputGuardRule? {
        val existing = findById(id) ?: return null
        val updatedAt = Instant.now()

        jdbcTemplate.update(
            """UPDATE output_guard_rules
               SET name = ?, pattern = ?, action = ?, priority = ?, enabled = ?, updated_at = ?
               WHERE id = ?""",
            rule.name,
            rule.pattern,
            rule.action.name,
            rule.priority,
            rule.enabled,
            java.sql.Timestamp.from(updatedAt),
            id
        )

        return existing.copy(
            name = rule.name,
            pattern = rule.pattern,
            action = rule.action,
            priority = rule.priority,
            enabled = rule.enabled,
            updatedAt = updatedAt
        )
    }

    override fun delete(id: String) {
        jdbcTemplate.update("DELETE FROM output_guard_rules WHERE id = ?", id)
    }

    companion object {
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            OutputGuardRule(
                id = rs.getString("id"),
                name = rs.getString("name"),
                pattern = rs.getString("pattern"),
                action = OutputGuardRuleAction.valueOf(rs.getString("action")),
                priority = rs.getInt("priority"),
                enabled = rs.getBoolean("enabled"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }
    }
}
