package com.arc.reactor.admin.query

import com.arc.reactor.admin.model.ApdexScore
import com.arc.reactor.admin.model.ErrorBudget
import com.arc.reactor.admin.model.SliMetric
import com.arc.reactor.admin.model.SloStatus
import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantPlan
import com.arc.reactor.admin.model.TenantStatus
import com.arc.reactor.admin.model.TenantUsage
import com.arc.reactor.admin.model.TimeSeriesPoint
import com.arc.reactor.admin.model.ToolUsageSummary
import com.arc.reactor.admin.model.UserUsageSummary
import com.arc.reactor.admin.tenant.InMemoryTenantStore
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class DashboardServiceTest {

    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val queryService = mockk<MetricQueryService>()
    private val sloService = mockk<SloService>()
    private val tenantStore = InMemoryTenantStore()

    private val dashboardService = DashboardService(jdbcTemplate, queryService, sloService, tenantStore)

    private val testTenant = Tenant(
        id = "t1",
        name = "Test Tenant",
        slug = "test",
        plan = TenantPlan.STARTER,
        status = TenantStatus.ACTIVE,
        sloAvailability = 0.995,
        sloLatencyP99Ms = 10000
    )

    private val now = Instant.now()
    private val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)

    @BeforeEach
    fun setUp() {
        tenantStore.save(testTenant)
    }

    @Nested
    inner class GetOverview {

        @Test
        fun `returns null for unknown tenant`() {
            dashboardService.getOverview("unknown").shouldBeNull()
        }

        @Test
        fun `assembles overview from all services`() {
            every { queryService.getSuccessRate(any(), any(), any()) } returns 0.98
            every { sloService.getApdex(any(), any(), any()) } returns
                ApdexScore(score = 0.85, satisfied = 800, tolerating = 150, frustrated = 50, total = 1000)
            every { sloService.getSloStatus(any(), any(), any()) } returns SloStatus(
                availability = SliMetric("Availability", 0.995, 0.98),
                latency = SliMetric("Latency P99", 10000.0, 3000.0),
                errorBudget = ErrorBudget(sloTarget = 0.995, budgetRemaining = 0.6)
            )
            every { queryService.getCurrentMonthUsage(any()) } returns
                TenantUsage("t1", 1000, 50000, BigDecimal("25.00"))
            every { jdbcTemplate.queryForObject(any<String>(), eq(Long::class.java), *anyVararg()) } returns 450L

            val overview = dashboardService.getOverview("t1")

            overview.shouldNotBeNull()
            overview.totalRequests shouldBe 1000
            overview.successRate shouldBe 0.98
            overview.avgResponseTimeMs shouldBe 450
            overview.apdexScore shouldBe 0.85
            overview.sloAvailability shouldBe 0.98
            overview.errorBudgetRemaining shouldBe 0.6
            overview.monthlyCost shouldBe BigDecimal("25.00")
        }
    }

    @Nested
    inner class GetUsage {

        @Test
        fun `assembles usage dashboard`() {
            every { queryService.getRequestTimeSeries(any(), any(), any()) } returns listOf(
                TimeSeriesPoint(now.minus(1, ChronoUnit.HOURS), 100.0)
            )
            every { queryService.getTopUsers(any(), any(), any()) } returns listOf(
                UserUsageSummary("user1", 50)
            )
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns
                listOf("web" to 80L, "api" to 20L)

            val usage = dashboardService.getUsage("t1", thirtyDaysAgo, now)

            usage.timeSeries shouldHaveSize 1
            usage.topUsers shouldHaveSize 1
            usage.channelDistribution["web"] shouldBe 80
            usage.channelDistribution["api"] shouldBe 20
        }
    }

    @Nested
    inner class GetQuality {

        @Test
        fun `assembles quality dashboard`() {
            every { queryService.getLatencyPercentiles(any(), any(), any()) } returns
                mapOf("p50" to 200L, "p95" to 1000L, "p99" to 3000L)
            every { queryService.getErrorDistribution(any(), any(), any()) } returns
                mapOf("TIMEOUT" to 10L, "TOOL_ERROR" to 5L)

            val quality = dashboardService.getQuality("t1", thirtyDaysAgo, now)

            quality.latencyP50 shouldBe 200
            quality.latencyP95 shouldBe 1000
            quality.latencyP99 shouldBe 3000
            quality.errorDistribution["TIMEOUT"] shouldBe 10
        }
    }

    @Nested
    inner class GetTools {

        @Test
        fun `assembles tool dashboard with ranking and slowest`() {
            val tools = listOf(
                ToolUsageSummary("tool1", 100, 0.95, 200, 500),
                ToolUsageSummary("tool2", 50, 0.90, 400, 1200),
                ToolUsageSummary("tool3", 30, 0.85, 600, 2000),
                ToolUsageSummary("tool4", 20, 0.80, 800, 3000),
                ToolUsageSummary("tool5", 10, 0.75, 1000, 4000),
                ToolUsageSummary("tool6", 5, 0.70, 1200, 5000)
            )
            every { queryService.getToolRanking(any(), any(), any()) } returns tools

            val dashboard = dashboardService.getTools("t1", thirtyDaysAgo, now)

            dashboard.toolRanking shouldHaveSize 6
            dashboard.slowestTools shouldHaveSize 5
            dashboard.slowestTools[0].toolName shouldBe "tool6"
        }
    }

    @Nested
    inner class GetCost {

        @Test
        fun `assembles cost dashboard`() {
            every { queryService.getCurrentMonthUsage(any()) } returns
                TenantUsage("t1", costUsd = BigDecimal("50.00"))
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns
                listOf("gpt-4" to BigDecimal("30.00"), "claude-3" to BigDecimal("20.00"))

            val cost = dashboardService.getCost("t1", thirtyDaysAgo, now)

            cost.monthlyCost shouldBe BigDecimal("50.00")
            cost.costByModel["gpt-4"] shouldBe BigDecimal("30.00")
            cost.costByModel["claude-3"] shouldBe BigDecimal("20.00")
        }
    }
}
