package com.arc.reactor.admin.alert

import com.arc.reactor.admin.model.AlertInstance
import com.arc.reactor.admin.model.AlertRule
import com.arc.reactor.admin.model.AlertSeverity
import com.arc.reactor.admin.model.AlertStatus
import com.arc.reactor.admin.model.AlertType
import java.util.concurrent.ConcurrentHashMap

interface AlertRuleStore {
    fun findRulesForTenant(tenantId: String): List<AlertRule>
    fun findPlatformRules(): List<AlertRule>
    fun findAllRules(): List<AlertRule>
    fun saveRule(rule: AlertRule): AlertRule
    fun deleteRule(id: String): Boolean
    fun findActiveAlerts(tenantId: String? = null): List<AlertInstance>
    fun saveAlert(alert: AlertInstance): AlertInstance
    fun resolveAlert(id: String)
}

class InMemoryAlertRuleStore : AlertRuleStore {
    private val rules = ConcurrentHashMap<String, AlertRule>()
    private val alerts = ConcurrentHashMap<String, AlertInstance>()

    override fun findRulesForTenant(tenantId: String): List<AlertRule> =
        rules.values.filter { it.tenantId == tenantId && it.enabled }

    override fun findPlatformRules(): List<AlertRule> =
        rules.values.filter { it.platformOnly && it.enabled }

    override fun findAllRules(): List<AlertRule> = rules.values.toList()

    override fun saveRule(rule: AlertRule): AlertRule {
        rules[rule.id] = rule
        return rule
    }

    override fun deleteRule(id: String): Boolean = rules.remove(id) != null

    override fun findActiveAlerts(tenantId: String?): List<AlertInstance> =
        alerts.values.filter {
            it.status == AlertStatus.ACTIVE &&
                (tenantId == null || it.tenantId == tenantId)
        }

    override fun saveAlert(alert: AlertInstance): AlertInstance {
        alerts[alert.id] = alert
        return alert
    }

    override fun resolveAlert(id: String) {
        alerts.computeIfPresent(id) { _, existing ->
            existing.copy(
                status = AlertStatus.RESOLVED,
                resolvedAt = java.time.Instant.now()
            )
        }
    }
}

/**
 * Default alert templates created when a tenant is provisioned.
 */
object DefaultAlertTemplates {

    fun forTenant(tenantId: String): List<AlertRule> = listOf(
        AlertRule(
            tenantId = tenantId,
            name = "High Error Rate",
            type = AlertType.STATIC_THRESHOLD,
            severity = AlertSeverity.CRITICAL,
            metric = "error_rate",
            threshold = 0.10,
            windowMinutes = 15
        ),
        AlertRule(
            tenantId = tenantId,
            name = "High Latency P99",
            type = AlertType.STATIC_THRESHOLD,
            severity = AlertSeverity.WARNING,
            metric = "latency_p99",
            threshold = 10000.0,
            windowMinutes = 10
        ),
        AlertRule(
            tenantId = tenantId,
            name = "Cost Anomaly",
            type = AlertType.BASELINE_ANOMALY,
            severity = AlertSeverity.WARNING,
            metric = "hourly_cost",
            threshold = 3.0, // 3 sigma
            windowMinutes = 60
        ),
        AlertRule(
            tenantId = tenantId,
            name = "Error Budget Fast Burn",
            type = AlertType.ERROR_BUDGET_BURN_RATE,
            severity = AlertSeverity.CRITICAL,
            metric = "burn_rate",
            threshold = 2.0,
            windowMinutes = 60
        ),
        AlertRule(
            tenantId = tenantId,
            name = "Token Budget 80%",
            type = AlertType.STATIC_THRESHOLD,
            severity = AlertSeverity.WARNING,
            metric = "token_budget_usage",
            threshold = 0.80,
            windowMinutes = 0 // monthly check
        ),
        AlertRule(
            tenantId = tenantId,
            name = "MCP Server Down",
            type = AlertType.STATIC_THRESHOLD,
            severity = AlertSeverity.CRITICAL,
            metric = "mcp_consecutive_failures",
            threshold = 3.0,
            windowMinutes = 0 // immediate
        )
    )

    fun platformRules(): List<AlertRule> = listOf(
        AlertRule(
            name = "MetricBuffer Overflow",
            type = AlertType.STATIC_THRESHOLD,
            severity = AlertSeverity.CRITICAL,
            metric = "pipeline_buffer_usage",
            threshold = 80.0,
            windowMinutes = 5,
            platformOnly = true
        ),
        AlertRule(
            name = "Aggregate Refresh Lag",
            type = AlertType.STATIC_THRESHOLD,
            severity = AlertSeverity.WARNING,
            metric = "aggregate_refresh_lag_ms",
            threshold = 600000.0, // 10 min
            windowMinutes = 0,
            platformOnly = true
        )
    )
}
