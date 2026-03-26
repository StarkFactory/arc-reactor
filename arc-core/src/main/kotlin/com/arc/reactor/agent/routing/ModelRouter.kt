package com.arc.reactor.agent.routing

import com.arc.reactor.agent.model.AgentCommand

/**
 * 동적 모델 라우팅 인터페이스.
 *
 * 요청의 복잡도, 비용, 사용자 설정 등을 기반으로
 * 적절한 LLM 모델을 선택하는 전략을 정의한다.
 *
 * ## 사용 예시
 * ```kotlin
 * val selection = modelRouter.route(command)
 * // selection.modelId → "gemini-2.5-flash" 또는 "gemini-2.5-pro" 등
 * ```
 *
 * @see CostAwareModelRouter 비용/품질 기반 기본 구현
 * @see ModelSelection 라우팅 결과
 */
interface ModelRouter {

    /**
     * 주어진 에이전트 명령을 분석하여 최적의 모델을 선택한다.
     *
     * @param command 분석할 에이전트 명령
     * @return 선택된 모델 ID와 선택 사유
     */
    fun route(command: AgentCommand): ModelSelection
}

/**
 * 모델 라우팅 결과.
 *
 * @param modelId 선택된 LLM 모델 식별자 (예: "gemini-2.5-flash", "claude-sonnet-4-20250514")
 * @param reason 모델 선택 사유 (로깅/디버깅용)
 * @param complexityScore 요청 복잡도 점수 (0.0 ~ 1.0). 라우터가 제공하지 않으면 null
 */
data class ModelSelection(
    val modelId: String,
    val reason: String,
    val complexityScore: Double? = null
)
