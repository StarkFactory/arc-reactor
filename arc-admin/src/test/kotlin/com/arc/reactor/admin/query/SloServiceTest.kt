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

/** [SloService]의 SLO 상태, 에러 예산 계산, APDEX 점수 산출 테스트 */
class SloServiceTest {

    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val queryService = mockk<MetricQueryService>()
    private val sloService = SloService(jdbcTemplate, queryService)

    private val now = Instant.now()
    private val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)

    @Nested
    inner class GetSloStatus {

        @Test
        fun `targets met일 때 healthy SLO status를 반환한다`() {
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
        fun `availability below target일 때 unhealthy를 반환한다`() {
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
        fun `latency exceeds target일 때 unhealthy를 반환한다`() {
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
        fun `zero requests에 대해 default budget를 반환한다`() {
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
        fun `consumed budget correctly를 계산한다`() {
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
        fun `healthy budget은(는) positive remaining를 가진다`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "total" to 10000L,
                "failed" to 10L
            )

            val budget = sloService.calculateErrorBudget("t1", 0.995, thirtyDaysAgo, now)

            budget.budgetRemaining shouldBeGreaterThan 0.0
            budget.burnRate shouldBeLessThan 1.0
        }

        @Test
        fun `exhausted budget은(는) zero remaining를 가진다`() {
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
        fun `apdex with satisfied, tolerating, frustrated distribution를 계산한다`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "satisfied" to 800L,
                "tolerating" to 150L,
                "frustrated" to 50L
            )

            val apdex = sloService.getApdex("t1", thirtyDaysAgo, now)

            // APDEX = (만족 + 허용/2) / 전체 = (800 + 150/2) / 1000 = 0.875
            apdex.score shouldBe 0.875
            apdex.satisfied shouldBe 800
            apdex.tolerating shouldBe 150
            apdex.frustrated shouldBe 50
            apdex.total shouldBe 1000
        }

        @Test
        fun `zero requests에 대해 1_0를 반환한다`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "satisfied" to 0L,
                "tolerating" to 0L,
                "frustrated" to 0L
            )

            val apdex = sloService.getApdex("t1", thirtyDaysAgo, now)

            apdex.score shouldBe 1.0
        }

        @Test
        fun `모든 satisfied results in perfect apdex`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "satisfied" to 1000L,
                "tolerating" to 0L,
                "frustrated" to 0L
            )

            sloService.getApdex("t1", thirtyDaysAgo, now).score shouldBe 1.0
        }

        @Test
        fun `모든 frustrated results in zero apdex`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "satisfied" to 0L,
                "tolerating" to 0L,
                "frustrated" to 1000L
            )

            sloService.getApdex("t1", thirtyDaysAgo, now).score shouldBe 0.0
        }
    }
}
