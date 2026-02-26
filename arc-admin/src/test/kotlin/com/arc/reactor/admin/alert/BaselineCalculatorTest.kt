package com.arc.reactor.admin.alert

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class BaselineCalculatorTest {

    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val calculator = BaselineCalculator(jdbcTemplate)

    @Nested
    inner class ComputeCostBaseline {

        @Test
        fun `returns baseline when sufficient samples`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "mean" to 10.5,
                "stddev" to 2.3,
                "samples" to 168L
            )

            val baseline = calculator.getBaseline("t1", "hourly_cost")

            baseline.shouldNotBeNull()
            baseline.mean shouldBe 10.5
            baseline.stdDev shouldBe 2.3
            baseline.sampleCount shouldBe 168
        }

        @Test
        fun `returns null when insufficient samples`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "mean" to 10.0,
                "stddev" to 2.0,
                "samples" to 5L
            )

            calculator.getBaseline("t1", "hourly_cost").shouldBeNull()
        }
    }

    @Nested
    inner class ComputeRequestBaseline {

        @Test
        fun `computes request baseline`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "mean" to 50.0,
                "stddev" to 10.0,
                "samples" to 100L
            )

            val baseline = calculator.getBaseline("t1", "hourly_requests")

            baseline.shouldNotBeNull()
            baseline.mean shouldBe 50.0
        }
    }

    @Nested
    inner class ComputeErrorRateBaseline {

        @Test
        fun `computes error rate baseline`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "mean" to 0.03,
                "stddev" to 0.01,
                "samples" to 168L
            )

            val baseline = calculator.getBaseline("t1", "error_rate")

            baseline.shouldNotBeNull()
            baseline.mean shouldBe 0.03
            baseline.stdDev shouldBe 0.01
        }
    }

    @Nested
    inner class UnknownMetric {

        @Test
        fun `returns null for unknown metric`() {
            calculator.getBaseline("t1", "unknown_metric").shouldBeNull()
        }
    }

    @Nested
    inner class CacheHit {

        @Test
        fun `second call uses cache instead of querying`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "mean" to 10.0,
                "stddev" to 2.0,
                "samples" to 168L
            )

            // First call triggers DB query
            val first = calculator.getBaseline("t1", "hourly_cost")
            first.shouldNotBeNull()

            // Second call should use cache
            val second = calculator.getBaseline("t1", "hourly_cost")
            second.shouldNotBeNull()
            second.mean shouldBe first.mean

            // Should only have queried once
            verify(exactly = 1) { jdbcTemplate.queryForMap(any(), *anyVararg()) }
        }
    }

    @Nested
    inner class NullHandling {

        @Test
        fun `handles null mean and stddev gracefully`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "mean" to null,
                "stddev" to null,
                "samples" to 50L
            )

            val baseline = calculator.getBaseline("t1", "hourly_cost")

            baseline.shouldNotBeNull()
            baseline.mean shouldBe 0.0
            baseline.stdDev shouldBe 0.0
        }

        @Test
        fun `handles null samples as zero`() {
            every { jdbcTemplate.queryForMap(any(), *anyVararg()) } returns mapOf(
                "mean" to 10.0,
                "stddev" to 2.0,
                "samples" to null
            )

            calculator.getBaseline("t1", "hourly_cost").shouldBeNull()
        }
    }
}
