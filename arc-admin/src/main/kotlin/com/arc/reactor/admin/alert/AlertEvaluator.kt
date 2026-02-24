package com.arc.reactor.admin.alert

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

class AlertEvaluator(
    private val alertStore: AlertRuleStore,
    private val queryService: MetricQueryService,
    private val sloService: SloService,
    private val tenantStore: TenantStore,
    private val baselineCalculator: BaselineCalculator
) {

    fun evaluateAll() {
        val tenants = tenantStore.findAll()
        for (tenant in tenants) {
            val rules = alertStore.findRulesForTenant(tenant.id)
            for (rule in rules) {
                try {
                    evaluate(rule)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to evaluate alert rule ${rule.id} for tenant ${tenant.id}" }
                }
            }
        }

        // Platform rules
        val platformRules = alertStore.findPlatformRules()
        for (rule in platformRules) {
            try {
                evaluate(rule)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to evaluate platform alert rule ${rule.id}" }
            }
        }
    }

    fun evaluate(rule: AlertRule) {
        val result = when (rule.type) {
            AlertType.STATIC_THRESHOLD -> evaluateStatic(rule)
            AlertType.BASELINE_ANOMALY -> evaluateAnomaly(rule)
            AlertType.ERROR_BUDGET_BURN_RATE -> evaluateBurnRate(rule)
        }

        if (result != null) {
            alertStore.saveAlert(result)
            logger.info { "Alert fired: ${rule.name} for tenant=${rule.tenantId ?: "platform"}" }
        }
    }

    private fun evaluateStatic(rule: AlertRule): AlertInstance? {
        val tenantId = rule.tenantId ?: return null
        val now = Instant.now()
        val from = now.minus(rule.windowMinutes.toLong().coerceAtLeast(1), ChronoUnit.MINUTES)

        val metricValue = when (rule.metric) {
            "error_rate" -> 1.0 - queryService.getSuccessRate(tenantId, from, now)
            "latency_p99" -> queryService.getLatencyPercentiles(tenantId, from, now)["p99"]?.toDouble() ?: 0.0
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

    private fun evaluateAnomaly(rule: AlertRule): AlertInstance? {
        val tenantId = rule.tenantId ?: return null
        val baseline = baselineCalculator.getBaseline(tenantId, rule.metric)
            ?: return null

        val now = Instant.now()
        val from = now.minus(rule.windowMinutes.toLong().coerceAtLeast(1), ChronoUnit.MINUTES)

        val current = when (rule.metric) {
            "hourly_cost" -> queryService.getCurrentMonthUsage(tenantId).costUsd.toDouble()
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
