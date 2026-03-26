package com.arc.reactor.agent.budget

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 모델별 토큰 가격 정보.
 *
 * @param inputPerMillionTokens 입력 토큰 백만 개당 USD 가격
 * @param outputPerMillionTokens 출력 토큰 백만 개당 USD 가격
 */
data class ModelPricing(
    val inputPerMillionTokens: Double,
    val outputPerMillionTokens: Double
)

/**
 * LLM 요청 비용 계산기 (실시간 예산 판단용).
 *
 * 모델별 가격표를 기반으로 입력/출력 토큰 수에 따른 USD 비용을 산출한다.
 * 가격표에 없는 모델은 경고 로그와 함께 0.0 USD를 반환한다.
 *
 * ## arc-admin CostCalculator와의 관계
 * arc-admin 모듈에도 동명의 CostCalculator가 존재하지만 설계 목적이 다르다:
 * - **이 클래스 (arc-core)**: 하드코딩 가격표, Double 정밀도, DB 호출 없음.
 *   ReAct 루프 내 [StepBudgetTracker]와 CostAwareModelRouter에서 사용.
 *   속도 우선 — 매 LLM 호출마다 실행되므로 I/O 없이 즉시 반환해야 한다.
 * - **arc-admin CostCalculator**: DB 기반 가격표, BigDecimal 정밀도,
 *   cached/reasoning 토큰 구분. 메트릭 기록 및 청구에 사용. 정밀도 우선.
 *
 * [DEFAULT_PRICING] 가격을 갱신할 때 arc-admin의 초기 seed 가격과도
 * 동기화하여 실시간 예산과 청구 간 편차를 최소화할 것.
 *
 * @param pricingTable 모델별 가격표. 기본값은 [DEFAULT_PRICING].
 * @see StepBudgetTracker 실시간 토큰 예산 추적
 */
class CostCalculator(
    private val pricingTable: Map<String, ModelPricing> = DEFAULT_PRICING
) {

    /**
     * 주어진 모델과 토큰 수로 비용을 계산한다.
     *
     * @param model LLM 모델 이름
     * @param inputTokens 입력 토큰 수 (0 이상)
     * @param outputTokens 출력 토큰 수 (0 이상)
     * @return 비용 계산 결과
     */
    fun calculateCost(model: String, inputTokens: Int, outputTokens: Int): CostRecord {
        require(inputTokens >= 0) { "inputTokens는 음수일 수 없다: $inputTokens" }
        require(outputTokens >= 0) { "outputTokens는 음수일 수 없다: $outputTokens" }

        val pricing = pricingTable[model]
        if (pricing == null) {
            logger.debug { "가격표에 없는 모델: $model — 비용을 0.0 USD로 반환" }
            return CostRecord(
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                estimatedCostUsd = 0.0
            )
        }

        val inputCost = inputTokens.toDouble() / MILLION * pricing.inputPerMillionTokens
        val outputCost = outputTokens.toDouble() / MILLION * pricing.outputPerMillionTokens
        val totalCost = inputCost + outputCost

        return CostRecord(
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            estimatedCostUsd = totalCost
        )
    }

    companion object {
        private const val MILLION = 1_000_000.0

        /** 주요 LLM 모델 기본 가격표 (USD / 백만 토큰). */
        val DEFAULT_PRICING: Map<String, ModelPricing> = mapOf(
            "gemini-2.5-flash" to ModelPricing(0.15, 0.60),
            "gemini-2.5-pro" to ModelPricing(1.25, 10.00),
            "gpt-4o" to ModelPricing(2.50, 10.00),
            "gpt-4o-mini" to ModelPricing(0.15, 0.60),
            "claude-sonnet-4-20250514" to ModelPricing(3.00, 15.00),
            "claude-opus-4-20250514" to ModelPricing(15.00, 75.00)
        )
    }
}
