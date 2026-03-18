package com.arc.reactor.agent.budget

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * CostCalculator 단위 테스트.
 *
 * 비용 계산의 정확성과 엣지 케이스를 검증한다.
 */
class CostCalculatorTest {

    private val calculator = CostCalculator()

    // --- 기본 비용 계산 ---

    @Test
    fun `gpt-4o 모델의 비용을 정확히 계산해야 한다`() {
        val cost = calculator.calculateCost("gpt-4o", inputTokens = 1_000_000, outputTokens = 0)

        assertEquals("gpt-4o", cost.model, "모델명이 일치해야 한다")
        assertEquals(1_000_000, cost.inputTokens, "입력 토큰 수가 일치해야 한다")
        assertEquals(0, cost.outputTokens, "출력 토큰 수가 일치해야 한다")
        assertEquals(2.50, cost.estimatedCostUsd, 0.001, "입력 100만 토큰 비용은 $2.50이어야 한다")
    }

    @Test
    fun `입력과 출력 토큰 비용을 합산해야 한다`() {
        val cost = calculator.calculateCost("gpt-4o", inputTokens = 1_000_000, outputTokens = 1_000_000)

        assertEquals(12.50, cost.estimatedCostUsd, 0.001, "입력 $2.50 + 출력 $10.00 = $12.50이어야 한다")
    }

    @Test
    fun `gemini-2_0-flash 모델의 저렴한 비용을 계산해야 한다`() {
        val cost = calculator.calculateCost("gemini-2.0-flash", inputTokens = 1000, outputTokens = 500)

        val expected = 1000.0 / 1_000_000 * 0.10 + 500.0 / 1_000_000 * 0.40
        assertEquals(expected, cost.estimatedCostUsd, 0.0001, "gemini-2.0-flash 비용이 정확해야 한다")
    }

    @Test
    fun `claude-opus-4 모델의 고비용을 계산해야 한다`() {
        val cost = calculator.calculateCost(
            "claude-opus-4-20250514",
            inputTokens = 10_000,
            outputTokens = 5_000
        )

        val expected = 10_000.0 / 1_000_000 * 15.00 + 5_000.0 / 1_000_000 * 75.00
        assertEquals(expected, cost.estimatedCostUsd, 0.0001, "claude-opus-4 비용이 정확해야 한다")
    }

    // --- 토큰 0인 경우 ---

    @Test
    fun `토큰이 0이면 비용도 0이어야 한다`() {
        val cost = calculator.calculateCost("gpt-4o", inputTokens = 0, outputTokens = 0)

        assertEquals(0.0, cost.estimatedCostUsd, 0.0, "토큰 0이면 비용도 0이어야 한다")
    }

    // --- 미등록 모델 ---

    @Test
    fun `가격표에 없는 모델은 비용 0을 반환해야 한다`() {
        val cost = calculator.calculateCost("unknown-model-xyz", inputTokens = 5000, outputTokens = 1000)

        assertEquals(0.0, cost.estimatedCostUsd, 0.0, "미등록 모델은 비용 0이어야 한다")
        assertEquals("unknown-model-xyz", cost.model, "모델명은 그대로 유지되어야 한다")
        assertEquals(5000, cost.inputTokens, "입력 토큰 수는 보존되어야 한다")
        assertEquals(1000, cost.outputTokens, "출력 토큰 수는 보존되어야 한다")
    }

    // --- 커스텀 가격표 ---

    @Test
    fun `커스텀 가격표로 비용을 계산해야 한다`() {
        val customPricing = mapOf("my-model" to ModelPricing(1.0, 2.0))
        val customCalculator = CostCalculator(customPricing)

        val cost = customCalculator.calculateCost("my-model", inputTokens = 1_000_000, outputTokens = 500_000)

        assertEquals(2.0, cost.estimatedCostUsd, 0.001, "커스텀 가격표 비용: 입력 $1.00 + 출력 $1.00 = $2.00")
    }

    // --- 입력 유효성 검증 ---

    @Test
    fun `음수 입력 토큰은 예외를 던져야 한다`() {
        val ex = assertThrows<IllegalArgumentException>("음수 입력 토큰은 거부되어야 한다") {
            calculator.calculateCost("gpt-4o", inputTokens = -1, outputTokens = 0)
        }
        assertTrue(ex.message!!.contains("inputTokens"), "예외 메시지에 inputTokens가 포함되어야 한다")
    }

    @Test
    fun `음수 출력 토큰은 예외를 던져야 한다`() {
        val ex = assertThrows<IllegalArgumentException>("음수 출력 토큰은 거부되어야 한다") {
            calculator.calculateCost("gpt-4o", inputTokens = 0, outputTokens = -1)
        }
        assertTrue(ex.message!!.contains("outputTokens"), "예외 메시지에 outputTokens가 포함되어야 한다")
    }

    // --- 기본 가격표 검증 ---

    @Test
    fun `기본 가격표에 모든 주요 모델이 포함되어야 한다`() {
        val expectedModels = listOf(
            "gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-pro",
            "gpt-4o", "gpt-4o-mini",
            "claude-sonnet-4-20250514", "claude-opus-4-20250514"
        )

        for (model in expectedModels) {
            assertTrue(
                CostCalculator.DEFAULT_PRICING.containsKey(model),
                "기본 가격표에 $model 이 포함되어야 한다"
            )
        }
    }
}
