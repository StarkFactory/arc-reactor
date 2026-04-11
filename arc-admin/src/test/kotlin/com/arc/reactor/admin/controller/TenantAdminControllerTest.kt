package com.arc.reactor.admin.controller

import com.arc.reactor.admin.alert.InMemoryAlertRuleStore
import com.arc.reactor.admin.collection.TenantResolver
import com.arc.reactor.admin.model.ErrorBudget
import com.arc.reactor.admin.model.OverviewDashboard
import com.arc.reactor.admin.model.SliMetric
import com.arc.reactor.admin.model.SloStatus
import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantPlan
import com.arc.reactor.admin.model.TenantStatus
import com.arc.reactor.admin.model.TenantUsage
import com.arc.reactor.admin.model.UsageDashboard
import com.arc.reactor.admin.query.DashboardService
import com.arc.reactor.admin.query.ExportService
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.query.SloService
import com.arc.reactor.admin.tenant.InMemoryTenantStore
import com.arc.reactor.auth.UserRole
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.math.BigDecimal

/** [TenantAdminController]의 테넌트별 대시보드, SLO, 쿼타, 내보내기 엔드포인트 테스트 */
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
        val headers = mockk<HttpHeaders>()
        every { headers.getFirst(TenantResolver.HEADER_NAME) } returns null
        val request = mockk<ServerHttpRequest>()
        every { request.headers } returns headers
        val attributes = mutableMapOf<String, Any>(
            TenantResolver.EXCHANGE_ATTR_KEY to "t1"
        )
        if (role != null) {
            attributes["userRole"] = role
        }
        every { exchange.attributes } returns attributes
        every { exchange.request } returns request
        return exchange
    }

    private fun exchangeWithTenant(tenantId: String, role: UserRole? = null): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val headers = mockk<HttpHeaders>()
        every { headers.getFirst(TenantResolver.HEADER_NAME) } returns null
        val request = mockk<ServerHttpRequest>()
        every { request.headers } returns headers
        val attributes = mutableMapOf<String, Any>(
            TenantResolver.EXCHANGE_ATTR_KEY to tenantId
        )
        if (role != null) {
            attributes["userRole"] = role
        }
        every { exchange.attributes } returns attributes
        every { exchange.request } returns request
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
        fun `overview은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.overview(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
            (response.body as AdminErrorResponse).error shouldBe "Admin access required"
        }

        @Test
        fun `usage은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.usage(null, null, exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `quality은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.quality(null, null, exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `tools은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.tools(null, null, exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `cost은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.cost(null, null, exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `slo은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.slo(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `alerts은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.alerts(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `quota은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.quota(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `exportExecutions은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.exportExecutions(null, null, exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `exportTools은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.exportTools(null, null, exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `overview은(는) allows ADMIN_MANAGER role`() {
            every { dashboardService.getOverview("t1") } returns OverviewDashboard()

            val response = runBlocking { controller.overview(exchangeWithRole(UserRole.ADMIN_MANAGER)) }

            response.statusCode shouldBe HttpStatus.OK
        }

        @Test
        fun `usage은(는) allows ADMIN_MANAGER role`() {
            every { dashboardService.getUsage(any(), any(), any()) } returns UsageDashboard()

            val response = runBlocking { controller.usage(null, null, exchangeWithRole(UserRole.ADMIN_MANAGER)) }

            response.statusCode shouldBe HttpStatus.OK
        }

        @Test
        fun `exportExecutions은(는) remains developer-admin only for ADMIN_MANAGER role`() {
            val response = runBlocking {
                controller.exportExecutions(null, null, exchangeWithRole(UserRole.ADMIN_MANAGER))
            }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }
    }

    @Nested
    inner class OverviewEndpoint {

        @Test
        fun `admin에 대해 200 with overview를 반환한다`() {
            val overview = OverviewDashboard(
                totalRequests = 1000,
                successRate = 0.98,
                avgResponseTimeMs = 450,
                apdexScore = 0.85,
                monthlyCost = BigDecimal("25.00")
            )
            every { dashboardService.getOverview("t1") } returns overview

            val response = runBlocking { controller.overview(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            response.body shouldBe overview
        }

        @Test
        fun `dashboard returns null일 때 404를 반환한다`() {
            every { dashboardService.getOverview("t1") } returns null

            val response = runBlocking { controller.overview(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }
    }

    @Nested
    inner class SloEndpoint {

        @Test
        fun `known tenant에 대해 SLO status를 반환한다`() {
            val sloStatus = SloStatus(
                availability = SliMetric("Availability", 0.995, 0.999),
                latency = SliMetric("Latency P99", 10000.0, 3000.0),
                errorBudget = ErrorBudget(sloTarget = 0.995)
            )
            every { sloService.getSloStatus("t1", 0.995, 10000L) } returns sloStatus

            val response = runBlocking { controller.slo(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            response.body shouldBe sloStatus
        }

        @Test
        fun `tenant not found일 때 404를 반환한다`() {
            val response = runBlocking { controller.slo(exchangeWithTenant("unknown", UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }
    }

    @Nested
    inner class QuotaEndpoint {

        @Test
        fun `admin에 대해 quota and usage를 반환한다`() {
            every { queryService.getCurrentMonthUsage("t1") } returns
                TenantUsage("t1", 500, 250000, BigDecimal("5.00"))

            val response = runBlocking { controller.quota(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any>
            body["quota"] shouldBe testTenant.quota
        }

        @Test
        fun `quota은(는) returns 404 for unknown tenant`() {
            val response = runBlocking { controller.quota(exchangeWithTenant("unknown", UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }
    }

    @Nested
    inner class AlertsEndpoint {

        @Test
        fun `tenant에 대해 active alerts를 반환한다`() {
            val response = runBlocking { controller.alerts(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<*>).size shouldBe 0
        }
    }

    @Nested
    inner class UsageEndpoint {

        @Test
        fun `200 with usage dashboard를 반환한다`() {
            every { dashboardService.getUsage(any(), any(), any()) } returns UsageDashboard()

            val response = runBlocking { controller.usage(null, null, exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
        }
    }

    @Nested
    inner class ExportEndpoints {

        @Test
        fun `exportExecutions은(는) returns CSV for admin`() {
            every { exportService.exportExecutionsCsv(any(), any(), any(), any()) } answers {
                val writer = arg<java.io.Writer>(3)
                writer.write("time,run_id\n")
            }

            val response = runBlocking { controller.exportExecutions(null, null, exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            response.headers["Content-Disposition"]?.first() shouldBe "attachment; filename=executions.csv"
        }
    }
}
