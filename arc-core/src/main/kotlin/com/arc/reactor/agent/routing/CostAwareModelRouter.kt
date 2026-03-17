package com.arc.reactor.agent.routing

import com.arc.reactor.agent.config.ModelRoutingProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 비용/품질 기반 동적 모델 라우터.
 *
 * 요청의 복잡도를 분석하여 단순 질문은 저렴한 모델(flash/haiku)로,
 * 복잡한 추론은 비싼 모델(pro/opus)로 라우팅한다.
 *
 * ## 판단 기준
 * - 입력 토큰 수 (userPrompt 길이 기반 추정)
 * - 도구 사용 여부 (ReAct 모드 + maxToolCalls)
 * - 사용자 설정 (command.model 명시 시 우선)
 * - 라우팅 전략 (cost-optimized, quality-first, balanced)
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     model-routing:
 *       enabled: true
 *       default-model: gemini-2.0-flash
 *       high-complexity-model: gemini-2.5-pro
 *       routing-strategy: balanced
 *       complexity-threshold-chars: 500
 * ```
 *
 * @param properties 모델 라우팅 설정
 * @see ModelRouter 인터페이스 정의
 */
class CostAwareModelRouter(
    private val properties: ModelRoutingProperties
) : ModelRouter {

    override fun route(command: AgentCommand): ModelSelection {
        // 사용자가 모델을 명시적으로 지정한 경우 우선 적용
        if (!command.model.isNullOrBlank()) {
            return ModelSelection(
                modelId = command.model,
                reason = "사용자 지정 모델"
            )
        }

        val complexity = analyzeComplexity(command)

        return when (properties.routingStrategy.lowercase()) {
            "cost-optimized" -> routeCostOptimized(complexity)
            "quality-first" -> routeQualityFirst(complexity)
            else -> routeBalanced(complexity)
        }.also { selection ->
            logger.debug {
                "모델 라우팅: complexity=$complexity, model=${selection.modelId}, reason=${selection.reason}"
            }
        }
    }

    /**
     * 요청의 복잡도를 분석한다.
     *
     * @return 복잡도 점수 (0.0 ~ 1.0)
     */
    internal fun analyzeComplexity(command: AgentCommand): Double {
        var score = 0.0

        // 입력 길이 기반 복잡도 (긴 입력 = 복잡할 가능성)
        val inputLength = command.userPrompt.length + command.systemPrompt.length
        if (inputLength > properties.complexityThresholdChars) {
            score += 0.3
        }
        if (inputLength > properties.complexityThresholdChars * 3) {
            score += 0.2
        }

        // 도구 사용 여부 (ReAct 모드 + 도구 호출 허용 = 복잡한 작업)
        if (command.mode == AgentMode.REACT && command.maxToolCalls > 1) {
            score += 0.3
        }

        // 대화 히스토리 길이 (긴 대화 = 컨텍스트 복잡)
        if (command.conversationHistory.size > 5) {
            score += 0.1
        }

        // 구조화 출력 요구 (JSON/YAML = 정확한 형식 필요)
        if (command.responseFormat != com.arc.reactor.agent.model.ResponseFormat.TEXT) {
            score += 0.1
        }

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 비용 최적화 전략: 가능한 한 저렴한 모델 사용.
     * 복잡도가 매우 높을 때만 고급 모델로 전환.
     */
    private fun routeCostOptimized(complexity: Double): ModelSelection {
        return if (complexity >= HIGH_COMPLEXITY_THRESHOLD) {
            ModelSelection(
                modelId = properties.highComplexityModel,
                reason = "비용 최적화: 높은 복잡도(${String.format("%.2f", complexity)}) → 고급 모델"
            )
        } else {
            ModelSelection(
                modelId = properties.defaultModel,
                reason = "비용 최적화: 낮은 복잡도(${String.format("%.2f", complexity)}) → 기본 모델"
            )
        }
    }

    /**
     * 품질 우선 전략: 가능한 한 고급 모델 사용.
     * 매우 단순한 요청만 저렴한 모델로 처리.
     */
    private fun routeQualityFirst(complexity: Double): ModelSelection {
        return if (complexity <= LOW_COMPLEXITY_THRESHOLD) {
            ModelSelection(
                modelId = properties.defaultModel,
                reason = "품질 우선: 매우 낮은 복잡도(${String.format("%.2f", complexity)}) → 기본 모델"
            )
        } else {
            ModelSelection(
                modelId = properties.highComplexityModel,
                reason = "품질 우선: 복잡도(${String.format("%.2f", complexity)}) → 고급 모델"
            )
        }
    }

    /**
     * 균형 전략 (기본): 중간 임계값으로 라우팅.
     */
    private fun routeBalanced(complexity: Double): ModelSelection {
        return if (complexity >= BALANCED_THRESHOLD) {
            ModelSelection(
                modelId = properties.highComplexityModel,
                reason = "균형: 높은 복잡도(${String.format("%.2f", complexity)}) → 고급 모델"
            )
        } else {
            ModelSelection(
                modelId = properties.defaultModel,
                reason = "균형: 낮은 복잡도(${String.format("%.2f", complexity)}) → 기본 모델"
            )
        }
    }

    companion object {
        /** 비용 최적화 전략에서 고급 모델로 전환하는 복잡도 임계값 */
        internal const val HIGH_COMPLEXITY_THRESHOLD = 0.7

        /** 품질 우선 전략에서 기본 모델을 허용하는 복잡도 임계값 */
        internal const val LOW_COMPLEXITY_THRESHOLD = 0.2

        /** 균형 전략에서 고급 모델로 전환하는 복잡도 임계값 */
        internal const val BALANCED_THRESHOLD = 0.5
    }
}
