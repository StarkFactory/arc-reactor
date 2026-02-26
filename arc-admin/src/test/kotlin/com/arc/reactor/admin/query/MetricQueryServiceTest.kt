package com.arc.reactor.admin.query

import com.arc.reactor.admin.model.TimeSeriesPoint
import com.arc.reactor.admin.model.ToolUsageSummary
import com.arc.reactor.admin.model.UserUsageSummary
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class MetricQueryServiceTest {

    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val service = MetricQueryService(jdbcTemplate)

    private val now = Instant.now()
    private val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)

    @Nested
    inner class GetCurrentMonthUsage {

        @Test
        fun `returns usage from aggregated query`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "requests" to 150L,
                "tokens" to 50000L,
                "cost" to BigDecimal("12.50")
            )

            val usage = service.getCurrentMonthUsage("t1")

            usage.tenantId shouldBe "t1"
            usage.requests shouldBe 150
            usage.tokens shouldBe 50000
            usage.costUsd shouldBe BigDecimal("12.50")
        }

        @Test
        fun `handles zero values`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "requests" to 0L,
                "tokens" to 0L,
                "cost" to null
            )

            val usage = service.getCurrentMonthUsage("t1")

            usage.requests shouldBe 0
            usage.tokens shouldBe 0
            usage.costUsd shouldBe BigDecimal.ZERO
        }
    }

    @Nested
    inner class GetSuccessRate {

        @Test
        fun `calculates success rate correctly`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "total" to 100L,
                "successful" to 85L
            )

            val rate = service.getSuccessRate("t1", thirtyDaysAgo, now)

            rate shouldBe 0.85
        }

        @Test
        fun `returns 1_0 for zero requests`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "total" to 0L,
                "successful" to 0L
            )

            service.getSuccessRate("t1", thirtyDaysAgo, now) shouldBe 1.0
        }
    }

    @Nested
    inner class GetLatencyPercentiles {

        @Test
        fun `returns all percentiles`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "p50" to 200L,
                "p95" to 1500L,
                "p99" to 5000L
            )

            val percentiles = service.getLatencyPercentiles("t1", thirtyDaysAgo, now)

            percentiles["p50"] shouldBe 200
            percentiles["p95"] shouldBe 1500
            percentiles["p99"] shouldBe 5000
        }

        @Test
        fun `handles null percentiles as zero`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "p50" to null,
                "p95" to null,
                "p99" to null
            )

            val percentiles = service.getLatencyPercentiles("t1", thirtyDaysAgo, now)

            percentiles["p50"] shouldBe 0
            percentiles["p95"] shouldBe 0
            percentiles["p99"] shouldBe 0
        }
    }

    @Nested
    inner class GetToolRanking {

        @Test
        fun `returns tool usage summaries`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns listOf(
                ToolUsageSummary("analyze_error", 100, 0.95, 250, 800, "error-log-mcp"),
                ToolUsageSummary("get_design", 50, 0.90, 400, 1200, "figma-mcp")
            )

            val ranking = service.getToolRanking("t1", thirtyDaysAgo, now)

            ranking shouldHaveSize 2
            ranking[0].toolName shouldBe "analyze_error"
            ranking[0].calls shouldBe 100
            ranking[1].mcpServerName shouldBe "figma-mcp"
        }
    }

    @Nested
    inner class GetErrorDistribution {

        @Test
        fun `returns error class counts`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns listOf(
                "TIMEOUT" to 25L,
                "TOOL_ERROR" to 10L,
                "unknown" to 5L
            )

            val dist = service.getErrorDistribution("t1", thirtyDaysAgo, now)

            dist["TIMEOUT"] shouldBe 25
            dist["TOOL_ERROR"] shouldBe 10
            dist["unknown"] shouldBe 5
        }
    }

    @Nested
    inner class GetRequestTimeSeries {

        @Test
        fun `uses hourly source for short ranges`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns emptyList<Any>()

            val from = now.minus(24, ChronoUnit.HOURS)
            service.getRequestTimeSeries("t1", from, now)

            verify {
                jdbcTemplate.query(
                    match<String> { it.contains("metric_executions_hourly") },
                    any<RowMapper<*>>(),
                    *anyVararg()
                )
            }
        }

        @Test
        fun `uses daily source for long ranges`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns emptyList<Any>()

            val from = now.minus(60, ChronoUnit.DAYS)
            service.getRequestTimeSeries("t1", from, now)

            verify {
                jdbcTemplate.query(
                    match<String> { it.contains("metric_executions_daily") },
                    any<RowMapper<*>>(),
                    *anyVararg()
                )
            }
        }

        @Test
        fun `uses explicit granularity when specified`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns emptyList<Any>()

            val from = now.minus(2, ChronoUnit.HOURS)
            service.getRequestTimeSeries("t1", from, now, "daily")

            verify {
                jdbcTemplate.query(
                    match<String> { it.contains("metric_executions_daily") },
                    any<RowMapper<*>>(),
                    *anyVararg()
                )
            }
        }
    }

    @Nested
    inner class GetTopUsers {

        @Test
        fun `returns user usage summaries`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns listOf(
                UserUsageSummary("user1", 50, lastActivity = now),
                UserUsageSummary("user2", 30, lastActivity = now)
            )

            val users = service.getTopUsers("t1", thirtyDaysAgo, now)

            users shouldHaveSize 2
            users[0].userId shouldBe "user1"
            users[0].requests shouldBe 50
        }
    }
}
