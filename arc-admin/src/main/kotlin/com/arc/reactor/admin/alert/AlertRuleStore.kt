package com.arc.reactor.admin.alert

import com.arc.reactor.admin.model.AlertInstance
import com.arc.reactor.admin.model.AlertRule
import com.arc.reactor.admin.model.AlertSeverity
import com.arc.reactor.admin.model.AlertStatus
import com.arc.reactor.admin.model.AlertType
import com.github.benmanes.caffeine.cache.Caffeine

/**
 * 알림 규칙 및 알림 인스턴스 저장소 인터페이스.
 *
 * @see JdbcAlertRuleStore JDBC 기반 구현
 * @see InMemoryAlertRuleStore 인메모리 구현 (테스트/DataSource 미사용 시)
 */
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

/** Caffeine 기반 인메모리 [AlertRuleStore] 구현체. */
class InMemoryAlertRuleStore : AlertRuleStore {
    private val rules = Caffeine.newBuilder()
        .maximumSize(10_000)
        .build<String, AlertRule>()
    private val alerts = Caffeine.newBuilder()
        .maximumSize(10_000)
        .build<String, AlertInstance>()

    override fun findRulesForTenant(tenantId: String): List<AlertRule> =
        rules.asMap().values.filter { it.tenantId == tenantId && it.enabled }

    override fun findPlatformRules(): List<AlertRule> =
        rules.asMap().values.filter { it.platformOnly && it.enabled }

    override fun findAllRules(): List<AlertRule> = rules.asMap().values.toList()

    override fun saveRule(rule: AlertRule): AlertRule {
        rules.put(rule.id, rule)
        return rule
    }

    override fun deleteRule(id: String): Boolean = rules.asMap().remove(id) != null

    override fun findActiveAlerts(tenantId: String?): List<AlertInstance> =
        alerts.asMap().values.filter {
            it.status == AlertStatus.ACTIVE &&
                (tenantId == null || it.tenantId == tenantId)
        }

    override fun saveAlert(alert: AlertInstance): AlertInstance {
        alerts.put(alert.id, alert)
        return alert
    }

    override fun resolveAlert(id: String) {
        val existing = alerts.getIfPresent(id) ?: return
        alerts.put(
            id,
            existing.copy(
                status = AlertStatus.RESOLVED,
                resolvedAt = java.time.Instant.now()
            )
        )
    }
}

/**
 * 테넌트 프로비저닝 시 생성되는 기본 알림 규칙 템플릿.
 *
 * 테넌트용: 높은 오류율, 높은 P99 지연, 비용 이상치, error budget 소진, 토큰 쿼터 80%, MCP 연속 실패.
 * 플랫폼용: 메트릭 버퍼 오버플로우, aggregate refresh lag.
 */
object DefaultAlertTemplates {

    /** 특정 테넌트용 기본 알림 규칙 목록을 생성한다. */
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
            threshold = 3.0, // 3 시그마
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
            windowMinutes = 0 // 월간 체크
        ),
        AlertRule(
            tenantId = tenantId,
            name = "MCP Server Down",
            type = AlertType.STATIC_THRESHOLD,
            severity = AlertSeverity.CRITICAL,
            metric = "mcp_consecutive_failures",
            threshold = 3.0,
            windowMinutes = 0 // 즉시 감지
        )
    )

    /** 플랫폼 전역 기본 알림 규칙 목록을 생성한다. */
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
            threshold = 600000.0, // 10분
            windowMinutes = 0,
            platformOnly = true
        )
    )
}
