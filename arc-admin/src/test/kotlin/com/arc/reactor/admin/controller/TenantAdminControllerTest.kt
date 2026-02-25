package com.arc.reactor.admin.controller

import com.arc.reactor.admin.alert.InMemoryAlertRuleStore
import com.arc.reactor.admin.collection.TenantResolver
import com.arc.reactor.admin.model.ApdexScore
import com.arc.reactor.admin.model.CostDashboard
import com.arc.reactor.admin.model.ErrorBudget
import com.arc.reactor.admin.model.OverviewDashboard
import com.arc.reactor.admin.model.QualityDashboard
import com.arc.reactor.admin.model.SliMetric
import com.arc.reactor.admin.model.SloStatus
import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantPlan
import com.arc.reactor.admin.model.TenantStatus
import com.arc.reactor.admin.model.TenantUsage
import com.arc.reactor.admin.model.ToolDashboard
import com.arc.reactor.admin.model.UsageDashboard
import com.arc.reactor.admin.query.DashboardService
import com.arc.reactor.admin.query.ExportService
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.query.SloService
import com.arc.reactor.admin.tenant.InMemoryTenantStore
import com.arc.reactor.auth.UserRole
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.math.BigDecimal

class TenantAdminControllerTest {

    private val tenantResolver = TenantResolver()
    private val tenantStore = InMemoryTenantStore()
    private val dashboardService = mockk<DashboardService>()
    private val queryService = mockk<MetricQueryService>()
    private val sloService = mockk<SloService>()
    private val alertStore = InMemoryAlertRuleStore()
    private val exportService = mockk<ExportService>()

    private val controller = TenantAdminController(
        tenantResolver, tenantStore, dashboardService, queryService, sloService, alertStore, exportService
    )

    private val testTenant = Tenant(
        id = "t1",
        name = "Test Tenant",
        slug = "test",
        plan = TenantPlan.STARTER,
        status = TenantStatus.ACTIVE,
        sloAvailability = 0.995,
        sloLatencyP99Ms = 10000
    )

    private fun exchangeWithRole(role: UserRole?): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attributes = mutableMapOf<String, Any>(
            TenantResolver.EXCHANGE_ATTR_KEY to "t1"
        )
        if (role != null) {
            attributes["userRole"] = role
        }
        every { exchange.attributes } returns attributes
        return exchange
    }

    private fun exchangeWithTenant(tenantId: String, role: UserRole? = null): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attributes = mutableMapOf<String, Any>(
            TenantResolver.EXCHANGE_ATTR_KEY to tenantId
        )
        if (role != null) {
            attributes["userRole"] = role
        }
        every { exchange.attributes } returns attributes
        return exchange
    }

    @BeforeEach
    fun setUp() {
        tenantStore.save(testTenant)
        tenantResolver.setTenantId("t1")
    }

    @Nested
    inner class Authentication {

        @Test
        fun `overview returns 403 for USER role`() {
            val response = controller.overview(exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
            (response.body as AdminErrorResponse).error shouldBe "Admin access required"
        }

        @Test
        fun `usage returns 403 for USER role`() {
            val response = controller.usage(null, null, exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `quality returns 403 for USER role`() {
            val response = controller.quality(null, null, exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `tools returns 403 for USER role`() {
            val response = controller.tools(null, null, exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `cost returns 403 for USER role`() {
            val response = controller.cost(null, null, exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `slo returns 403 for USER role`() {
            val response = controller.slo(exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `alerts returns 403 for USER role`() {
            val response = controller.alerts(exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `quota returns 403 for USER role`() {
            val response = controller.quota(exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `exportExecutions returns 403 for USER role`() {
            val response = controller.exportExecutions(null, null, exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `exportTools returns 403 for USER role`() {
            val response = controller.exportTools(null, null, exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }
    }

    @Nested
    inner class OverviewEndpoint {

        @Test
        fun `returns 200 with overview for admin`() {
            val overview = OverviewDashboard(
                totalRequests = 1000,
                successRate = 0.98,
                avgResponseTimeMs = 450,
                apdexScore = 0.85,
                monthlyCost = BigDecimal("25.00")
            )
            every { dashboardService.getOverview("t1") } returns overview

            val response = controller.overview(exchangeWithRole(null))

            response.statusCode shouldBe HttpStatus.OK
            response.body shouldBe overview
        }

        @Test
        fun `returns 404 when dashboard returns null`() {
            every { dashboardService.getOverview("t1") } returns null

            val response = controller.overview(exchangeWithRole(null))

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }
    }

    @Nested
    inner class SloEndpoint {

        @Test
        fun `returns SLO status for known tenant`() {
            val sloStatus = SloStatus(
                availability = SliMetric("Availability", 0.995, 0.999),
                latency = SliMetric("Latency P99", 10000.0, 3000.0),
                errorBudget = ErrorBudget(sloTarget = 0.995)
            )
            every { sloService.getSloStatus("t1", 0.995, 10000L) } returns sloStatus

            val response = controller.slo(exchangeWithRole(null))

            response.statusCode shouldBe HttpStatus.OK
            response.body shouldBe sloStatus
        }

        @Test
        fun `returns 404 when tenant not found`() {
            val response = controller.slo(exchangeWithTenant("unknown"))

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }
    }

    @Nested
    inner class QuotaEndpoint {

        @Test
        fun `returns quota and usage for admin`() {
            every { queryService.getCurrentMonthUsage("t1") } returns
                TenantUsage("t1", 500, 250000, BigDecimal("5.00"))

            val response = controller.quota(exchangeWithRole(null))

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any>
            body["quota"] shouldBe testTenant.quota
        }

        @Test
        fun `quota returns 404 for unknown tenant`() {
            val response = controller.quota(exchangeWithTenant("unknown"))

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }
    }

    @Nested
    inner class AlertsEndpoint {

        @Test
        fun `returns active alerts for tenant`() {
            val response = controller.alerts(exchangeWithRole(null))

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<*>).size shouldBe 0
        }
    }

    @Nested
    inner class UsageEndpoint {

        @Test
        fun `returns 200 with usage dashboard`() {
            every { dashboardService.getUsage(any(), any(), any()) } returns UsageDashboard()

            val response = controller.usage(null, null, exchangeWithRole(null))

            response.statusCode shouldBe HttpStatus.OK
        }
    }

    @Nested
    inner class ExportEndpoints {

        @Test
        fun `exportExecutions returns CSV for admin`() {
            every { exportService.exportExecutionsCsv(any(), any(), any(), any()) } answers {
                val writer = arg<java.io.Writer>(3)
                writer.write("time,run_id\n")
            }

            val response = controller.exportExecutions(null, null, exchangeWithRole(null))

            response.statusCode shouldBe HttpStatus.OK
            response.headers["Content-Disposition"]?.first() shouldBe "attachment; filename=executions.csv"
        }
    }
}
