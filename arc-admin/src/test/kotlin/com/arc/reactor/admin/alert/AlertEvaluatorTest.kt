package com.arc.reactor.admin.alert

import com.arc.reactor.admin.collection.PipelineHealthMonitor
import com.arc.reactor.admin.model.AlertRule
import com.arc.reactor.admin.model.AlertType
import com.arc.reactor.admin.model.ErrorBudget
import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantPlan
import com.arc.reactor.admin.model.TenantQuota
import com.arc.reactor.admin.model.TenantStatus
import com.arc.reactor.admin.model.TenantUsage
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.query.SloService
import com.arc.reactor.admin.tenant.InMemoryTenantStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AlertEvaluatorTest {

    private val alertStore = InMemoryAlertRuleStore()
    private val queryService = mockk<MetricQueryService>()
    private val sloService = mockk<SloService>()
    private val tenantStore = InMemoryTenantStore()
    private val baselineCalculator = mockk<BaselineCalculator>()
    private val healthMonitor = PipelineHealthMonitor()

    private val evaluator = AlertEvaluator(
        alertStore = alertStore,
        queryService = queryService,
        sloService = sloService,
        tenantStore = tenantStore,
        baselineCalculator = baselineCalculator,
        healthMonitor = healthMonitor
    )

    private val testTenant = Tenant(
        id = "t1",
        name = "Test",
        slug = "test",
        plan = TenantPlan.STARTER,
        status = TenantStatus.ACTIVE,
        sloAvailability = 0.995
    )

    @BeforeEach
    fun setup() {
        tenantStore.save(testTenant)
    }

    @Nested
    inner class StaticThreshold {

        @Test
        fun `fires when error rate exceeds threshold`() {
            val rule = AlertRule(
                tenantId = "t1",
                name = "High Error Rate",
                type = AlertType.STATIC_THRESHOLD,
                metric = "error_rate",
                threshold = 0.10,
                windowMinutes = 15
            )

            // success rate = 0.85, so error rate = 0.15 > 0.10
            every { queryService.getSuccessRate("t1", any(), any()) } returns 0.85

            evaluator.evaluate(rule)

            val alerts = alertStore.findActiveAlerts("t1")
            alerts.size shouldBe 1
            alerts[0].message shouldContain "error_rate"
        }

        @Test
        fun `does not fire when error rate is below threshold`() {
            val rule = AlertRule(
                tenantId = "t1",
                name = "High Error Rate",
                type = AlertType.STATIC_THRESHOLD,
                metric = "error_rate",
                threshold = 0.10,
                windowMinutes = 15
            )

            // success rate = 0.95, error rate = 0.05 < 0.10
            every { queryService.getSuccessRate("t1", any(), any()) } returns 0.95

            evaluator.evaluate(rule)

            alertStore.findActiveAlerts("t1").size shouldBe 0
        }

        @Test
        fun `fires when latency p99 exceeds threshold`() {
            val rule = AlertRule(
                tenantId = "t1",
                name = "High Latency",
                type = AlertType.STATIC_THRESHOLD,
                metric = "latency_p99",
                threshold = 5000.0,
                windowMinutes = 10
            )

            every { queryService.getLatencyPercentiles("t1", any(), any()) } returns
                mapOf("p50" to 1000L, "p95" to 4000L, "p99" to 8000L)

            evaluator.evaluate(rule)

            val alerts = alertStore.findActiveAlerts("t1")
            alerts.size shouldBe 1
            alerts[0].metricValue shouldBe 8000.0
        }
    }

    @Nested
    inner class BaselineAnomaly {

        @Test
        fun `fires when current value exceeds baseline + threshold sigma`() {
            val rule = AlertRule(
                tenantId = "t1",
                name = "Cost Anomaly",
                type = AlertType.BASELINE_ANOMALY,
                metric = "hourly_cost",
                threshold = 3.0, // 3 sigma
                windowMinutes = 60
            )

            every { baselineCalculator.getBaseline("t1", "hourly_cost") } returns
                Baseline(mean = 10.0, stdDev = 2.0, sampleCount = 168)

            // Mock getHourlyCost to return high hourly cost (25 > 10 + 3*2 = 16)
            every { queryService.getHourlyCost("t1", any(), any()) } returns 25.0

            evaluator.evaluate(rule)

            val alerts = alertStore.findActiveAlerts("t1")
            alerts.size shouldBe 1
            alerts[0].message shouldContain "baseline"
        }

        @Test
        fun `does not fire when baseline is unavailable`() {
            val rule = AlertRule(
                tenantId = "t1",
                name = "Cost Anomaly",
                type = AlertType.BASELINE_ANOMALY,
                metric = "hourly_cost",
                threshold = 3.0,
                windowMinutes = 60
            )

            every { baselineCalculator.getBaseline("t1", "hourly_cost") } returns null

            evaluator.evaluate(rule)

            alertStore.findActiveAlerts("t1").size shouldBe 0
        }
    }

    @Nested
    inner class BurnRate {

        @Test
        fun `fires when burn rate exceeds threshold`() {
            val rule = AlertRule(
                tenantId = "t1",
                name = "Fast Burn",
                type = AlertType.ERROR_BUDGET_BURN_RATE,
                metric = "burn_rate",
                threshold = 2.0,
                windowMinutes = 60
            )

            every { sloService.calculateErrorBudget("t1", 0.995, any(), any()) } returns
                ErrorBudget(
                    sloTarget = 0.995,
                    windowDays = 30,
                    totalRequests = 10000,
                    failedRequests = 200,
                    currentAvailability = 0.98,
                    budgetTotal = 50,
                    budgetConsumed = 200,
                    budgetRemaining = 0.0,
                    burnRate = 4.0 // 4x > 2x threshold
                )

            evaluator.evaluate(rule)

            val alerts = alertStore.findActiveAlerts("t1")
            alerts.size shouldBe 1
            alerts[0].message shouldContain "burn_rate"
        }
    }

    @Nested
    inner class TokenBudgetUsage {

        @Test
        fun `fires when token usage exceeds budget threshold`() {
            tenantStore.save(testTenant.copy(quota = TenantQuota(maxTokensPerMonth = 10000)))

            val rule = AlertRule(
                tenantId = "t1",
                name = "Token Budget 80%",
                type = AlertType.STATIC_THRESHOLD,
                metric = "token_budget_usage",
                threshold = 0.80,
                windowMinutes = 0
            )

            every { queryService.getCurrentMonthUsage("t1") } returns
                TenantUsage(tenantId = "t1", requests = 50, tokens = 9000) // 90% > 80%

            evaluator.evaluate(rule)

            val alerts = alertStore.findActiveAlerts("t1")
            alerts.size shouldBe 1
            alerts[0].message shouldContain "token_budget_usage"
        }

        @Test
        fun `does not fire when token usage is below threshold`() {
            tenantStore.save(testTenant.copy(quota = TenantQuota(maxTokensPerMonth = 10000)))

            val rule = AlertRule(
                tenantId = "t1",
                name = "Token Budget 80%",
                type = AlertType.STATIC_THRESHOLD,
                metric = "token_budget_usage",
                threshold = 0.80,
                windowMinutes = 0
            )

            every { queryService.getCurrentMonthUsage("t1") } returns
                TenantUsage(tenantId = "t1", requests = 20, tokens = 5000) // 50% < 80%

            evaluator.evaluate(rule)

            alertStore.findActiveAlerts("t1").size shouldBe 0
        }
    }

    @Nested
    inner class McpConsecutiveFailures {

        @Test
        fun `fires when consecutive failures exceed threshold`() {
            val rule = AlertRule(
                tenantId = "t1",
                name = "MCP Server Down",
                type = AlertType.STATIC_THRESHOLD,
                metric = "mcp_consecutive_failures",
                threshold = 3.0,
                windowMinutes = 0
            )

            every { queryService.getMaxConsecutiveMcpFailures("t1") } returns 5L

            evaluator.evaluate(rule)

            val alerts = alertStore.findActiveAlerts("t1")
            alerts.size shouldBe 1
            alerts[0].message shouldContain "mcp_consecutive_failures"
        }
    }

    @Nested
    inner class PlatformMetrics {

        @Test
        fun `fires when pipeline buffer usage exceeds threshold`() {
            healthMonitor.updateBufferUsage(90.0)

            val rule = AlertRule(
                name = "MetricBuffer Overflow",
                type = AlertType.STATIC_THRESHOLD,
                metric = "pipeline_buffer_usage",
                threshold = 80.0,
                windowMinutes = 5,
                platformOnly = true
            )

            evaluator.evaluate(rule)

            val alerts = alertStore.findActiveAlerts(null)
            alerts.size shouldBe 1
            alerts[0].message shouldContain "pipeline_buffer_usage"
        }

        @Test
        fun `fires when aggregate refresh lag exceeds threshold`() {
            val rule = AlertRule(
                name = "Aggregate Refresh Lag",
                type = AlertType.STATIC_THRESHOLD,
                metric = "aggregate_refresh_lag_ms",
                threshold = 600000.0,
                windowMinutes = 0,
                platformOnly = true
            )

            every { queryService.getAggregateRefreshLagMs() } returns 900000L // 15min > 10min

            evaluator.evaluate(rule)

            val alerts = alertStore.findActiveAlerts(null)
            alerts.size shouldBe 1
            alerts[0].message shouldContain "aggregate_refresh_lag_ms"
        }
    }

    @Nested
    inner class DefaultTemplates {

        @Test
        fun `creates correct number of tenant templates`() {
            val rules = DefaultAlertTemplates.forTenant("t1")
            rules.size shouldBe 6
            rules.all { it.tenantId == "t1" } shouldBe true
        }

        @Test
        fun `creates correct number of platform templates`() {
            val rules = DefaultAlertTemplates.platformRules()
            rules.size shouldBe 2
            rules.all { it.platformOnly } shouldBe true
        }
    }
}
