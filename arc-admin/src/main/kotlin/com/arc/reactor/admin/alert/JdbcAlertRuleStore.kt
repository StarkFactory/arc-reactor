package com.arc.reactor.admin.alert

import com.arc.reactor.admin.model.AlertInstance
import com.arc.reactor.admin.model.AlertRule
import com.arc.reactor.admin.model.AlertSeverity
import com.arc.reactor.admin.model.AlertStatus
import com.arc.reactor.admin.model.AlertType
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

class JdbcAlertRuleStore(
    private val jdbcTemplate: JdbcTemplate
) : AlertRuleStore {

    override fun findRulesForTenant(tenantId: String): List<AlertRule> =
        jdbcTemplate.query(
            "SELECT * FROM alert_rules WHERE tenant_id = ? AND enabled = TRUE",
            RULE_ROW_MAPPER, tenantId
        )

    override fun findPlatformRules(): List<AlertRule> =
        jdbcTemplate.query(
            "SELECT * FROM alert_rules WHERE platform_only = TRUE AND enabled = TRUE",
            RULE_ROW_MAPPER
        )

    override fun findAllRules(): List<AlertRule> =
        jdbcTemplate.query("SELECT * FROM alert_rules ORDER BY created_at DESC", RULE_ROW_MAPPER)

    override fun saveRule(rule: AlertRule): AlertRule {
        jdbcTemplate.update(
            """INSERT INTO alert_rules (id, tenant_id, name, description, type, severity, metric,
                   threshold, window_minutes, enabled, platform_only, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (id) DO UPDATE SET
                   tenant_id = EXCLUDED.tenant_id, name = EXCLUDED.name,
                   description = EXCLUDED.description, type = EXCLUDED.type,
                   severity = EXCLUDED.severity, metric = EXCLUDED.metric,
                   threshold = EXCLUDED.threshold, window_minutes = EXCLUDED.window_minutes,
                   enabled = EXCLUDED.enabled, platform_only = EXCLUDED.platform_only""",
            rule.id, rule.tenantId, rule.name, rule.description,
            rule.type.name, rule.severity.name, rule.metric,
            rule.threshold, rule.windowMinutes, rule.enabled, rule.platformOnly,
            Timestamp.from(rule.createdAt)
        )
        return rule
    }

    override fun deleteRule(id: String): Boolean {
        val count = jdbcTemplate.update("DELETE FROM alert_rules WHERE id = ?", id)
        return count > 0
    }

    override fun findActiveAlerts(tenantId: String?): List<AlertInstance> {
        return if (tenantId != null) {
            jdbcTemplate.query(
                "SELECT * FROM alert_instances WHERE status = 'ACTIVE' AND tenant_id = ? ORDER BY fired_at DESC",
                ALERT_ROW_MAPPER, tenantId
            )
        } else {
            jdbcTemplate.query(
                "SELECT * FROM alert_instances WHERE status = 'ACTIVE' ORDER BY fired_at DESC",
                ALERT_ROW_MAPPER
            )
        }
    }

    override fun saveAlert(alert: AlertInstance): AlertInstance {
        jdbcTemplate.update(
            """INSERT INTO alert_instances (id, rule_id, tenant_id, severity, status, message,
                   metric_value, threshold, fired_at, resolved_at, acknowledged_by)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            alert.id, alert.ruleId, alert.tenantId, alert.severity.name, alert.status.name,
            alert.message, alert.metricValue, alert.threshold,
            Timestamp.from(alert.firedAt),
            alert.resolvedAt?.let { Timestamp.from(it) },
            alert.acknowledgedBy
        )
        return alert
    }

    override fun resolveAlert(id: String) {
        jdbcTemplate.update(
            "UPDATE alert_instances SET status = 'RESOLVED', resolved_at = NOW() WHERE id = ?",
            id
        )
    }

    companion object {
        private val RULE_ROW_MAPPER = { rs: ResultSet, _: Int ->
            AlertRule(
                id = rs.getString("id"),
                tenantId = rs.getString("tenant_id"),
                name = rs.getString("name"),
                description = rs.getString("description") ?: "",
                type = AlertType.valueOf(rs.getString("type")),
                severity = AlertSeverity.valueOf(rs.getString("severity")),
                metric = rs.getString("metric"),
                threshold = rs.getDouble("threshold"),
                windowMinutes = rs.getInt("window_minutes"),
                enabled = rs.getBoolean("enabled"),
                platformOnly = rs.getBoolean("platform_only"),
                createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.now()
            )
        }

        private val ALERT_ROW_MAPPER = { rs: ResultSet, _: Int ->
            AlertInstance(
                id = rs.getString("id"),
                ruleId = rs.getString("rule_id"),
                tenantId = rs.getString("tenant_id"),
                severity = AlertSeverity.valueOf(rs.getString("severity")),
                status = AlertStatus.valueOf(rs.getString("status")),
                message = rs.getString("message"),
                metricValue = rs.getDouble("metric_value"),
                threshold = rs.getDouble("threshold"),
                firedAt = rs.getTimestamp("fired_at")?.toInstant() ?: Instant.now(),
                resolvedAt = rs.getTimestamp("resolved_at")?.toInstant(),
                acknowledgedBy = rs.getString("acknowledged_by")
            )
        }
    }
}
