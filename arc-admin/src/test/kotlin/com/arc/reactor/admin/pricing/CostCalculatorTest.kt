package com.arc.reactor.admin.pricing

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CostCalculatorTest {

    private lateinit var store: InMemoryModelPricingStore
    private lateinit var calculator: CostCalculator

    @BeforeEach
    fun setUp() {
        store = InMemoryModelPricingStore()
        calculator = CostCalculator(store)

        // GPT-4o pricing
        store.save(
            ModelPricing(
                provider = "openai",
                model = "gpt-4o",
                promptPricePer1k = BigDecimal("0.005"),
                completionPricePer1k = BigDecimal("0.015"),
                cachedInputPricePer1k = BigDecimal("0.0025"),
                reasoningPricePer1k = BigDecimal("0.015")
            )
        )

        // Gemini Flash pricing
        store.save(
            ModelPricing(
                provider = "google",
                model = "gemini-2.0-flash",
                promptPricePer1k = BigDecimal("0.000075"),
                completionPricePer1k = BigDecimal("0.0003"),
                cachedInputPricePer1k = BigDecimal("0.00001875"),
                reasoningPricePer1k = BigDecimal("0.0003")
            )
        )
    }

    @Nested
    inner class BasicCalculation {

        @Test
        fun `should calculate cost for standard prompt and completion`() {
            val cost = calculator.calculate(
                provider = "openai",
                model = "gpt-4o",
                time = Instant.now(),
                promptTokens = 1000,
                completionTokens = 500
            )

            // 1000 * 0.005/1000 + 500 * 0.015/1000 = 0.005 + 0.0075 = 0.0125
            cost shouldBe BigDecimal("0.01250000")
        }

        @Test
        fun `should apply cached token discount`() {
            val fullCost = calculator.calculate(
                provider = "openai",
                model = "gpt-4o",
                time = Instant.now(),
                promptTokens = 1000,
                cachedTokens = 0,
                completionTokens = 500
            )

            val cachedCost = calculator.calculate(
                provider = "openai",
                model = "gpt-4o",
                time = Instant.now(),
                promptTokens = 1000,
                cachedTokens = 800,
                completionTokens = 500
            )

            fullCost shouldBeGreaterThan cachedCost
        }

        @Test
        fun `should include reasoning tokens`() {
            val withoutReasoning = calculator.calculate(
                provider = "openai",
                model = "gpt-4o",
                time = Instant.now(),
                promptTokens = 1000,
                completionTokens = 500
            )

            val withReasoning = calculator.calculate(
                provider = "openai",
                model = "gpt-4o",
                time = Instant.now(),
                promptTokens = 1000,
                completionTokens = 500,
                reasoningTokens = 200
            )

            withReasoning shouldBeGreaterThan withoutReasoning
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should return zero for unknown model`() {
            val cost = calculator.calculate(
                provider = "unknown",
                model = "unknown-model",
                time = Instant.now(),
                promptTokens = 1000,
                completionTokens = 500
            )

            cost shouldBe BigDecimal.ZERO
        }

        @Test
        fun `should handle zero tokens`() {
            val cost = calculator.calculate(
                provider = "openai",
                model = "gpt-4o",
                time = Instant.now(),
                promptTokens = 0,
                completionTokens = 0
            )

            cost.compareTo(BigDecimal.ZERO) shouldBe 0
        }

        @Test
        fun `should not go negative when cached exceeds prompt`() {
            val cost = calculator.calculate(
                provider = "openai",
                model = "gpt-4o",
                time = Instant.now(),
                promptTokens = 100,
                cachedTokens = 200,
                completionTokens = 500
            )

            cost shouldBeGreaterThan BigDecimal.ZERO
        }
    }
}
