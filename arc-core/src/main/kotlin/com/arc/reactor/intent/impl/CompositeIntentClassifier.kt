package com.arc.reactor.intent.impl

import com.arc.reactor.intent.IntentClassifier
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.IntentResult
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 복합 인텐트 분류기 (규칙 -> LLM 캐스케이딩)
 *
 * 2단계 분류 전략:
 * 1. **규칙 기반** — 토큰 비용 없음, 높은 신뢰도의 키워드 패턴 처리
 * 2. **LLM 기반** — 시맨틱 이해가 필요한 모호한 입력에 대한 폴백
 *
 * LLM 분류기가 호출되는 경우:
 * - 규칙 기반이 unknown을 반환 (키워드 매칭 없음), 또는
 * - 규칙 기반 신뢰도가 [ruleConfidenceThreshold] 미만
 *
 * ## 토큰 절약 효과
 * 운영 트래픽에서 규칙 기반이 일반적으로 10-20%의 요청을 비용 없이 처리한다.
 * 나머지 80-90%가 LLM으로 넘어간다 (요청당 약 200-500 토큰).
 *
 * WHY: 규칙 기반으로 확실한 패턴을 먼저 걸러내어 LLM 호출 비용을 절감한다.
 * 모든 요청을 LLM으로 보내면 불필요한 비용이 발생하고, 모든 요청을 규칙으로만
 * 처리하면 모호한 입력을 놓친다. 캐스케이딩으로 두 장점을 결합한다.
 *
 * @param ruleClassifier 규칙 기반 분류기 (1단계)
 * @param llmClassifier LLM 기반 분류기 (폴백 단계)
 * @param ruleConfidenceThreshold LLM 건너뛰기를 위한 최소 규칙 기반 신뢰도 (기본값 0.8)
 * @see RuleBasedIntentClassifier 규칙 기반 구현
 * @see LlmIntentClassifier LLM 기반 구현
 */
class CompositeIntentClassifier(
    private val ruleClassifier: IntentClassifier,
    private val llmClassifier: IntentClassifier,
    private val ruleConfidenceThreshold: Double = 0.8
) : IntentClassifier {

    override suspend fun classify(text: String, context: ClassificationContext): IntentResult {
        // 1단계: 규칙 기반 분류 시도
        val ruleResult = ruleClassifier.classify(text, context)
        val rulePrimary = ruleResult.primary

        // 규칙 기반 결과의 신뢰도가 임계값 이상이면 즉시 반환한다
        if (!ruleResult.isUnknown && rulePrimary != null && rulePrimary.confidence >= ruleConfidenceThreshold) {
            logger.debug {
                "복합분류기: 규칙 기반 매칭 수락 " +
                    "(인텐트=${rulePrimary.intentName}, 신뢰도=${rulePrimary.confidence})"
            }
            return ruleResult
        }

        // 2단계: LLM 폴백 — 규칙으로 충분하지 않은 경우
        try {
            val llmResult = llmClassifier.classify(text, context)

            logger.debug {
                val ruleInfo = if (ruleResult.isUnknown) "규칙 매칭 없음"
                else "규칙=${rulePrimary?.intentName}(${rulePrimary?.confidence})"
                "복합분류기: LLM 폴백 사용 ($ruleInfo) -> " +
                    "llm=${llmResult.primary?.intentName}(${llmResult.primary?.confidence})"
            }

            return llmResult
        } catch (e: Exception) {
            // CancellationException은 반드시 재전파
            e.throwIfCancellation()
            // LLM 실패 시 규칙 기반 결과로 폴백한다
            logger.error(e) { "복합분류기: LLM 분류 실패, 규칙 결과로 폴백" }
            return ruleResult
        }
    }
}
