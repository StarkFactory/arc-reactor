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
        fun `and stop without errors를 시작한다`() {
            scheduler.start()
            scheduler.stop()
        }

        @Test
        fun `destroy은(는) calls stop`() {
            scheduler.start()
            scheduler.destroy()
            // 예외를 던지면 안 됩니다
        }

        @Test
        fun `can은(는) stop without starting`() {
            scheduler.stop()
        }
    }

    @Nested
    inner class EvaluationDispatch {

        @Test
        fun `alert and dispatches notification를 발생시킨다`() {
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

            // evaluation manually (same logic as scheduler's runEvaluation)를 트리거합니다
            val beforeCount = alertStore.findActiveAlerts().size
            evaluator.evaluateAll()
            val afterCount = alertStore.findActiveAlerts().size
            val newAlerts = afterCount - beforeCount

            newAlerts shouldBe 1

            // what scheduler does with new alerts를 시뮬레이션합니다
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
        fun `dispatch when no new alerts하지 않는다`() {
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
        fun `evaluation exception은(는) caught and logged이다`() {
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

            // evaluateAll은(는) not throw — errors are caught per rule해야 합니다
            evaluator.evaluateAll()

            alertStore.findActiveAlerts().size shouldBe 0
        }
    }

    @Nested
    inner class MultipleRules {

        @Test
        fun `rules from multiple tenants를 평가한다`() {
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

            // 두 tenants have high error rate
            every { queryService.getSuccessRate(any(), any(), any()) } returns 0.80

            evaluator.evaluateAll()

            alertStore.findActiveAlerts("t1").size shouldBe 1
            alertStore.findActiveAlerts("t2").size shouldBe 1
        }
    }
}
