package com.arc.reactor.admin.query

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit

class SloServiceTest {

    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val queryService = mockk<MetricQueryService>()
    private val sloService = SloService(jdbcTemplate, queryService)

    private val now = Instant.now()
    private val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)

    @Nested
    inner class GetSloStatus {

        @Test
        fun `returns healthy SLO status when targets met`() {
            every { queryService.getSuccessRate(any(), any(), any()) } returns 0.999
            every { queryService.getLatencyPercentiles(any(), any(), any()) } returns
                mapOf("p50" to 200L, "p95" to 1000L, "p99" to 3000L)
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "total" to 10000L,
                "failed" to 10L
            )

            val status = sloService.getSloStatus("t1", 0.995, 10000L)

            status.availability.current shouldBe 0.999
            status.availability.isHealthy shouldBe true
            status.latency.current shouldBe 3000.0
            status.latency.isHealthy shouldBe true
        }

        @Test
        fun `returns unhealthy when availability below target`() {
            every { queryService.getSuccessRate(any(), any(), any()) } returns 0.98
            every { queryService.getLatencyPercentiles(any(), any(), any()) } returns
                mapOf("p50" to 200L, "p95" to 1000L, "p99" to 3000L)
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "total" to 10000L,
                "failed" to 200L
            )

            val status = sloService.getSloStatus("t1", 0.995, 10000L)

            status.availability.isHealthy shouldBe false
        }

        @Test
        fun `returns unhealthy when latency exceeds target`() {
            every { queryService.getSuccessRate(any(), any(), any()) } returns 0.999
            every { queryService.getLatencyPercentiles(any(), any(), any()) } returns
                mapOf("p50" to 5000L, "p95" to 15000L, "p99" to 25000L)
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "total" to 10000L,
                "failed" to 10L
            )

            val status = sloService.getSloStatus("t1", 0.995, 10000L)

            status.latency.isHealthy shouldBe false
        }
    }

    @Nested
    inner class CalculateErrorBudget {

        @Test
        fun `returns default budget for zero requests`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "total" to 0L,
                "failed" to 0L
            )

            val budget = sloService.calculateErrorBudget("t1", 0.995, thirtyDaysAgo, now)

            budget.totalRequests shouldBe 0
            budget.failedRequests shouldBe 0
            budget.currentAvailability shouldBe 1.0
            budget.budgetRemaining shouldBe 1.0
        }

        @Test
        fun `calculates consumed budget correctly`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "total" to 10000L,
                "failed" to 200L
            )

            val budget = sloService.calculateErrorBudget("t1", 0.995, thirtyDaysAgo, now)

            budget.totalRequests shouldBe 10000
            budget.failedRequests shouldBe 200
            budget.currentAvailability shouldBe 0.98
            budget.budgetRemaining shouldBe 0.0 // 200 failures > 50 budget
        }

        @Test
        fun `healthy budget has positive remaining`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "total" to 10000L,
                "failed" to 10L
            )

            val budget = sloService.calculateErrorBudget("t1", 0.995, thirtyDaysAgo, now)

            budget.budgetRemaining shouldBeGreaterThan 0.0
            budget.burnRate shouldBeLessThan 1.0
        }

        @Test
        fun `exhausted budget has zero remaining`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "total" to 1000L,
                "failed" to 100L
            )

            val budget = sloService.calculateErrorBudget("t1", 0.995, thirtyDaysAgo, now)

            budget.budgetRemaining shouldBe 0.0
        }
    }

    @Nested
    inner class GetApdex {

        @Test
        fun `calculates apdex with satisfied, tolerating, frustrated distribution`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "satisfied" to 800L,
                "tolerating" to 150L,
                "frustrated" to 50L
            )

            val apdex = sloService.getApdex("t1", thirtyDaysAgo, now)

            // (800 + 150/2) / 1000 = 0.875
            apdex.score shouldBe 0.875
            apdex.satisfied shouldBe 800
            apdex.tolerating shouldBe 150
            apdex.frustrated shouldBe 50
            apdex.total shouldBe 1000
        }

        @Test
        fun `returns 1_0 for zero requests`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "satisfied" to 0L,
                "tolerating" to 0L,
                "frustrated" to 0L
            )

            val apdex = sloService.getApdex("t1", thirtyDaysAgo, now)

            apdex.score shouldBe 1.0
        }

        @Test
        fun `all satisfied results in perfect apdex`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "satisfied" to 1000L,
                "tolerating" to 0L,
                "frustrated" to 0L
            )

            sloService.getApdex("t1", thirtyDaysAgo, now).score shouldBe 1.0
        }

        @Test
        fun `all frustrated results in zero apdex`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "satisfied" to 0L,
                "tolerating" to 0L,
                "frustrated" to 1000L
            )

            sloService.getApdex("t1", thirtyDaysAgo, now).score shouldBe 0.0
        }
    }
}
