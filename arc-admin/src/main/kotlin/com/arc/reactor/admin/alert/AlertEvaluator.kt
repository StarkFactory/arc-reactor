package com.arc.reactor.admin.alert

import com.arc.reactor.admin.collection.PipelineHealthMonitor
import com.arc.reactor.admin.model.AlertInstance
import com.arc.reactor.admin.model.AlertRule
import com.arc.reactor.admin.model.AlertType
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.query.SloService
import com.arc.reactor.admin.tenant.TenantStore
import mu.KotlinLogging
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * 알림 규칙을 평가하여 조건 충족 시 [AlertInstance]를 생성하는 평가기.
 *
 * 지원 알림 유형:
 * - **STATIC_THRESHOLD**: 메트릭 값이 정적 임계값을 초과하면 발동
 * - **BASELINE_ANOMALY**: 7일 baseline 대비 N-sigma 이상 편차 시 발동
 * - **ERROR_BUDGET_BURN_RATE**: SLO error budget 소진 속도 초과 시 발동
 *
 * @see AlertScheduler 주기적으로 evaluateAll()을 호출하는 스케줄러
 * @see AlertRuleStore 규칙 및 알림 인스턴스 저장소
 */
class AlertEvaluator(
    private val alertStore: AlertRuleStore,
    private val queryService: MetricQueryService,
    private val sloService: SloService,
    private val tenantStore: TenantStore,
    private val baselineCalculator: BaselineCalculator,
    private val healthMonitor: PipelineHealthMonitor? = null
) {

    /**
     * 전체 규칙을 배치 로드 후 순회하며 평가한다.
     * 테넌트별 개별 쿼리(N+1) 대신 findAllRules() 단일 쿼리로 전체를 가져온다.
     * 개별 규칙 실패는 경고 로깅 후 건너뛴다.
     */
    fun evaluateAll() {
        // 단일 쿼리로 전체 규칙 로드 (N+1 방지)
        val allRules = alertStore.findAllRules().filter { it.enabled }

        for (rule in allRules) {
            try {
                evaluate(rule)
            } catch (e: Exception) {
                logger.warn(e) { "알림 규칙 평가 실패: ruleId=${rule.id}, tenant=${rule.tenantId ?: "platform"}" }
            }
        }
    }

    /**
     * 단일 규칙을 평가하여 조건 충족 시 알림을 저장하고 로깅한다.
     * 동일 ruleId에 대해 ACTIVE 알림이 이미 존재하면 중복 생성하지 않는다.
     */
    fun evaluate(rule: AlertRule) {
        val result = when (rule.type) {
            AlertType.STATIC_THRESHOLD -> evaluateStatic(rule)
            AlertType.BASELINE_ANOMALY -> evaluateAnomaly(rule)
            AlertType.ERROR_BUDGET_BURN_RATE -> evaluateBurnRate(rule)
        }

        if (result != null) {
            val activeAlerts = alertStore.findActiveAlerts(rule.tenantId)
            val alreadyFiring = activeAlerts.any { it.ruleId == rule.id }
            if (alreadyFiring) {
                logger.debug { "Alert already active for ruleId=${rule.id}, skipping duplicate" }
                return
            }
            alertStore.saveAlert(result)
            logger.info { "Alert fired: ${rule.name} for tenant=${rule.tenantId ?: "platform"}" }
        }
    }

    /** 정적 임계값 알림을 평가한다. 테넌트 범위 또는 플랫폼 범위 분기. */
    private fun evaluateStatic(rule: AlertRule): AlertInstance? {
        if (rule.platformOnly) return evaluatePlatformStatic(rule)

        val tenantId = rule.tenantId ?: return null
        val now = Instant.now()
        val from = now.minus(rule.windowMinutes.toLong().coerceAtLeast(1), ChronoUnit.MINUTES)

        val metricValue = when (rule.metric) {
            "error_rate" -> 1.0 - queryService.getSuccessRate(tenantId, from, now)
            "latency_p99" -> queryService.getLatencyPercentiles(tenantId, from, now)["p99"]?.toDouble() ?: 0.0
            "token_budget_usage" -> {
                val tenant = tenantStore.findById(tenantId) ?: return null
                if (tenant.quota.maxTokensPerMonth <= 0) return null
                val usage = queryService.getCurrentMonthUsage(tenantId)
                usage.tokens.toDouble() / tenant.quota.maxTokensPerMonth
            }
            "mcp_consecutive_failures" -> {
                queryService.getMaxConsecutiveMcpFailures(tenantId)?.toDouble() ?: 0.0
            }
            else -> return null
        }

        if (metricValue > rule.threshold) {
            return AlertInstance(
                ruleId = rule.id,
                tenantId = rule.tenantId,
                severity = rule.severity,
                message = "${rule.name}: ${rule.metric} = ${"%.4f".format(metricValue)} (threshold: ${rule.threshold})",
                metricValue = metricValue,
                threshold = rule.threshold
            )
        }
        return null
    }

    /** 플랫폼 전역 정적 임계값 알림 (buffer 사용률, aggregate refresh lag). */
    private fun evaluatePlatformStatic(rule: AlertRule): AlertInstance? {
        val metricValue = when (rule.metric) {
            "pipeline_buffer_usage" -> healthMonitor?.bufferUsagePercent ?: 0.0
            "aggregate_refresh_lag_ms" -> queryService.getAggregateRefreshLagMs()?.toDouble() ?: 0.0
            else -> return null
        }

        if (metricValue > rule.threshold) {
            return AlertInstance(
                ruleId = rule.id,
                tenantId = null,
                severity = rule.severity,
                message = "${rule.name}: ${rule.metric} = ${"%.2f".format(metricValue)} (threshold: ${rule.threshold})",
                metricValue = metricValue,
                threshold = rule.threshold
            )
        }
        return null
    }

    /** baseline 대비 이상치 알림을 평가한다 (N-sigma 방식). */
    private fun evaluateAnomaly(rule: AlertRule): AlertInstance? {
        val tenantId = rule.tenantId ?: return null
        val baseline = baselineCalculator.getBaseline(tenantId, rule.metric)
            ?: return null

        val now = Instant.now()
        val from = now.minus(rule.windowMinutes.toLong().coerceAtLeast(1), ChronoUnit.MINUTES)

        val current = when (rule.metric) {
            "hourly_cost" -> queryService.getHourlyCost(tenantId, from, now)
            else -> return null
        }

        val sigma = rule.threshold
        if (current > baseline.mean + sigma * baseline.stdDev) {
            return AlertInstance(
                ruleId = rule.id,
                tenantId = rule.tenantId,
                severity = rule.severity,
                message = "${rule.name}: ${"%.4f".format(current)} > baseline ${"%.4f".format(baseline.mean)} + ${sigma}σ",
                metricValue = current,
                threshold = baseline.mean + sigma * baseline.stdDev
            )
        }
        return null
    }

    /** SLO error budget burn rate 알림을 평가한다. */
    private fun evaluateBurnRate(rule: AlertRule): AlertInstance? {
        val tenantId = rule.tenantId ?: return null
        val tenant = tenantStore.findById(tenantId) ?: return null

        val now = Instant.now()
        val windowStart = now.minus(rule.windowMinutes.toLong().coerceAtLeast(1), ChronoUnit.MINUTES)

        val budget = sloService.calculateErrorBudget(
            tenantId, tenant.sloAvailability, windowStart, now
        )

        if (budget.burnRate > rule.threshold) {
            return AlertInstance(
                ruleId = rule.id,
                tenantId = rule.tenantId,
                severity = rule.severity,
                message = "${rule.name}: burn_rate = ${"%.2f".format(budget.burnRate)}x (threshold: ${rule.threshold}x)",
                metricValue = budget.burnRate,
                threshold = rule.threshold
            )
        }
        return null
    }
}
