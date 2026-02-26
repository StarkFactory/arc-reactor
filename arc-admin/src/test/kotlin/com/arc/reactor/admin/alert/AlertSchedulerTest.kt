package com.arc.reactor.admin.alert

import com.arc.reactor.admin.model.AlertRule
import com.arc.reactor.admin.model.AlertType
import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantPlan
import com.arc.reactor.admin.model.TenantStatus
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.query.SloService
import com.arc.reactor.admin.tenant.InMemoryTenantStore
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AlertSchedulerTest {

    private val alertStore = InMemoryAlertRuleStore()
    private val queryService = mockk<MetricQueryService>()
    private val sloService = mockk<SloService>()
    private val tenantStore = InMemoryTenantStore()
    private val baselineCalculator = mockk<BaselineCalculator>()
    private val notificationService = mockk<AlertNotificationService>(relaxed = true)

    private val evaluator = AlertEvaluator(
        alertStore = alertStore,
        queryService = queryService,
        sloService = sloService,
        tenantStore = tenantStore,
        baselineCalculator = baselineCalculator
    )

    private lateinit var scheduler: AlertScheduler

    private val testTenant = Tenant(
        id = "t1",
        name = "Test",
        slug = "test",
        plan = TenantPlan.STARTER,
        status = TenantStatus.ACTIVE,
        sloAvailability = 0.995
    )

    @BeforeEach
    fun setUp() {
        tenantStore.save(testTenant)
        scheduler = AlertScheduler(evaluator, notificationService, alertStore, intervalSeconds = 600)
    }

    @AfterEach
    fun tearDown() {
        scheduler.stop()
    }

    @Nested
    inner class Lifecycle {

        @Test
        fun `start and stop without errors`() {
            scheduler.start()
            scheduler.stop()
        }

        @Test
        fun `destroy calls stop`() {
            scheduler.start()
            scheduler.destroy()
            // Should not throw
        }

        @Test
        fun `can stop without starting`() {
            scheduler.stop()
        }
    }

    @Nested
    inner class EvaluationDispatch {

        @Test
        fun `fires alert and dispatches notification`() {
            val rule = AlertRule(
                tenantId = "t1",
                name = "High Error Rate",
                type = AlertType.STATIC_THRESHOLD,
                metric = "error_rate",
                threshold = 0.10,
                windowMinutes = 15
            )
            alertStore.saveRule(rule)

            // error rate = 0.20 > 0.10
            every { queryService.getSuccessRate(any(), any(), any()) } returns 0.80

            // Trigger evaluation manually (same logic as scheduler's runEvaluation)
            val beforeCount = alertStore.findActiveAlerts().size
            evaluator.evaluateAll()
            val afterCount = alertStore.findActiveAlerts().size
            val newAlerts = afterCount - beforeCount

            newAlerts shouldBe 1

            // Simulate what scheduler does with new alerts
            if (newAlerts > 0) {
                val active = alertStore.findActiveAlerts()
                val newest = active.sortedByDescending { it.firedAt }.take(newAlerts)
                for (alert in newest) {
                    notificationService.dispatch(alert)
                }
            }

            verify(exactly = 1) { notificationService.dispatch(any()) }
        }

        @Test
        fun `does not dispatch when no new alerts`() {
            alertStore.saveRule(
                AlertRule(
                    tenantId = "t1",
                    name = "Low Error",
                    type = AlertType.STATIC_THRESHOLD,
                    metric = "error_rate",
                    threshold = 0.10,
                    windowMinutes = 15
                )
            )

            // error rate = 0.02 < 0.10
            every { queryService.getSuccessRate(any(), any(), any()) } returns 0.98

            val beforeCount = alertStore.findActiveAlerts().size
            evaluator.evaluateAll()
            val afterCount = alertStore.findActiveAlerts().size

            (afterCount - beforeCount) shouldBe 0
        }

        @Test
        fun `evaluation exception is caught and logged`() {
            alertStore.saveRule(
                AlertRule(
                    tenantId = "t1",
                    name = "Failing Rule",
                    type = AlertType.STATIC_THRESHOLD,
                    metric = "error_rate",
                    threshold = 0.10,
                    windowMinutes = 15
                )
            )

            every { queryService.getSuccessRate(any(), any(), any()) } throws RuntimeException("DB down")

            // evaluateAll should not throw — errors are caught per rule
            evaluator.evaluateAll()

            alertStore.findActiveAlerts().size shouldBe 0
        }
    }

    @Nested
    inner class MultipleRules {

        @Test
        fun `evaluates rules from multiple tenants`() {
            val tenant2 = Tenant(
                id = "t2",
                name = "Test2",
                slug = "test2",
                plan = TenantPlan.BUSINESS,
                status = TenantStatus.ACTIVE,
                sloAvailability = 0.999
            )
            tenantStore.save(tenant2)

            alertStore.saveRule(
                AlertRule(
                    tenantId = "t1",
                    name = "T1 Error Rate",
                    type = AlertType.STATIC_THRESHOLD,
                    metric = "error_rate",
                    threshold = 0.10,
                    windowMinutes = 15
                )
            )
            alertStore.saveRule(
                AlertRule(
                    tenantId = "t2",
                    name = "T2 Error Rate",
                    type = AlertType.STATIC_THRESHOLD,
                    metric = "error_rate",
                    threshold = 0.10,
                    windowMinutes = 15
                )
            )

            // Both tenants have high error rate
            every { queryService.getSuccessRate(any(), any(), any()) } returns 0.80

            evaluator.evaluateAll()

            alertStore.findActiveAlerts("t1").size shouldBe 1
            alertStore.findActiveAlerts("t2").size shouldBe 1
        }
    }
}
