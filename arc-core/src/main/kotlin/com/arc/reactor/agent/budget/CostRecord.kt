package com.arc.reactor.agent.budget

/**
 * 단일 요청의 비용 계산 결과.
 *
 * @param model 사용된 LLM 모델 이름
 * @param inputTokens 입력 토큰 수
 * @param outputTokens 출력 토큰 수
 * @param estimatedCostUsd 추정 비용 (USD)
 */
data class CostRecord(
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCostUsd: Double
)
