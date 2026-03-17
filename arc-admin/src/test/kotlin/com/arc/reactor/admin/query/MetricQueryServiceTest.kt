package com.arc.reactor.admin.query

import com.arc.reactor.admin.model.ToolUsageSummary
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

/** [MetricQueryService]의 사용량, 성공률, 지연시간, 도구 랭킹, 시계열 조회 테스트 */
class MetricQueryServiceTest {

    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val service = MetricQueryService(jdbcTemplate)

    private val now = Instant.now()
    private val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)

    @Nested
    inner class GetCurrentMonthUsage {

        @Test
        fun `usage from aggregated query를 반환한다`() {
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
        fun `zero values를 처리한다`() {
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
        fun `success rate correctly를 계산한다`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "total" to 100L,
                "successful" to 85L
            )

            val rate = service.getSuccessRate("t1", thirtyDaysAgo, now)

            rate shouldBe 0.85
        }

        @Test
        fun `zero requests에 대해 1_0를 반환한다`() {
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
        fun `all percentiles를 반환한다`() {
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
        fun `null percentiles as zero를 처리한다`() {
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
        fun `tool usage summaries를 반환한다`() {
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
        fun `error class counts를 반환한다`() {
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
        fun `hourly source for short ranges를 사용한다`() {
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
        fun `daily source for long ranges를 사용한다`() {
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
        fun `specified일 때 explicit granularity를 사용한다`() {
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
        fun `anonymized user usage summaries를 반환한다`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns listOf(
                50L to now,
                30L to now
            )

            val users = service.getTopUsers("t1", thirtyDaysAgo, now)

            users shouldHaveSize 2
            users[0].userLabel shouldBe "User-1"
            users[0].requests shouldBe 50
        }

        @Test
        fun `channel summaries when user identifiers are unavailable로 폴백한다`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returnsMany listOf(
                emptyList<Pair<Long, Instant?>>(),
                listOf(Triple("slack", 42L, now))
            )

            val users = service.getTopUsers("t1", thirtyDaysAgo, now)

            users shouldHaveSize 1
            users[0].userLabel shouldBe "Channel:slack"
            users[0].requests shouldBe 42
        }
    }
}
