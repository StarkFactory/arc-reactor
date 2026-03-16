package com.arc.reactor.intent.impl

import com.arc.reactor.intent.IntentClassifier
import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.ClassifiedIntent
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 규칙 기반 인텐트 분류기
 *
 * 대소문자 무관 포함 매칭으로 사용자 입력과 인텐트 키워드를 비교한다.
 * 토큰 비용 없음 — LLM 호출 없음. 높은 신뢰도 패턴에만 적합하다.
 *
 * ## 향상된 기능
 * - **동의어**: 각 키워드에 동의어를 설정할 수 있다. 어떤 동의어가 매칭되어도 하나의 매칭으로 계산
 * - **가중치**: 키워드별 커스텀 가중치 설정 가능 (기본값 1.0), 신뢰도에 영향
 * - **부정 키워드**: 부정 키워드가 매칭되면 해당 인텐트를 즉시 제외
 *
 * WHY: LLM 호출 없이 확실한 키워드 패턴을 빠르게 분류한다.
 * 운영 트래픽의 10-20%를 비용 없이 처리하여 전체 비용을 절감한다.
 * 모호한 입력은 CompositeIntentClassifier를 통해 LLM으로 폴백된다.
 *
 * @param registry 키워드가 포함된 인텐트 정의의 소스
 * @see CompositeIntentClassifier 캐스케이딩 전략에서의 활용
 * @see LlmIntentClassifier LLM 기반 대안
 */
class RuleBasedIntentClassifier(
    private val registry: IntentRegistry
) : IntentClassifier {

    override suspend fun classify(text: String, context: ClassificationContext): IntentResult {
        val startTime = System.nanoTime()
        val normalizedText = text.lowercase().trim()
        val matches = mutableListOf<ClassifiedIntent>()

        // 활성화된 모든 인텐트에 대해 키워드 매칭을 수행한다
        for (intent in registry.listEnabled()) {
            if (intent.keywords.isEmpty()) continue
            val scored = scoreIntent(intent, normalizedText) ?: continue
            matches.add(scored)
        }

        val latencyMs = (System.nanoTime() - startTime) / 1_000_000

        if (matches.isEmpty()) {
            logger.debug { "규칙기반: 키워드 매칭 없음 (입력길이=${text.length})" }
            return IntentResult.unknown(classifiedBy = CLASSIFIER_NAME, latencyMs = latencyMs)
        }

        // 신뢰도 내림차순으로 정렬 — 가장 높은 신뢰도가 주요 인텐트
        val sorted = matches.sortedByDescending { it.confidence }
        val result = IntentResult(
            primary = sorted.first(),
            secondary = sorted.drop(1),
            classifiedBy = CLASSIFIER_NAME,
            tokenCost = 0,
            latencyMs = latencyMs
        )

        logger.debug {
            "규칙기반: 매칭 인텐트=${result.primary?.intentName} " +
                "신뢰도=${result.primary?.confidence} 대안수=${sorted.size - 1}"
        }
        return result
    }

    /**
     * 정규화된 입력 텍스트에 대해 단일 인텐트의 점수를 매긴다.
     *
     * 점수 계산 과정:
     * 1. 부정 키워드 매칭 확인 — 매칭되면 즉시 제외 (null 반환)
     * 2. 각 키워드(+ 동의어)의 가중치를 합산하여 신뢰도 계산
     *
     * @return 키워드가 하나 이상 매칭되면 ClassifiedIntent, 제외되거나 매칭 없으면 null
     */
    private fun scoreIntent(intent: IntentDefinition, normalizedText: String): ClassifiedIntent? {
        // 1. 부정 키워드 — 하나라도 매칭되면 이 인텐트를 즉시 제외
        for (neg in intent.negativeKeywords) {
            if (normalizedText.contains(neg.lowercase())) return null
        }

        // 2. 각 키워드를 점수화 (동의어와 가중치 적용)
        var matchedWeight = 0.0
        var totalWeight = 0.0

        for (keyword in intent.keywords) {
            val weight = intent.keywordWeights[keyword] ?: 1.0
            totalWeight += weight
            // 원래 키워드 + 동의어를 모두 포함하는 유효 키워드 목록 생성
            val variants = buildEffectiveKeywords(keyword, intent.synonyms)
            if (variants.any { normalizedText.contains(it.lowercase()) }) {
                matchedWeight += weight
            }
        }

        if (matchedWeight <= 0.0) return null
        // 신뢰도 = 매칭된 가중치 / 전체 가중치 (최대 1.0)
        val confidence = (matchedWeight / totalWeight).coerceAtMost(1.0)
        return ClassifiedIntent(intentName = intent.name, confidence = confidence)
    }

    /**
     * 유효 키워드 집합을 구성한다: 원래 키워드 + 동의어.
     *
     * @param keyword 원래 키워드
     * @param synonyms 동의어 맵
     * @return 원래 키워드와 동의어를 포함하는 리스트
     */
    private fun buildEffectiveKeywords(
        keyword: String,
        synonyms: Map<String, List<String>>
    ): List<String> {
        val syns = synonyms[keyword]
        return if (syns.isNullOrEmpty()) listOf(keyword) else listOf(keyword) + syns
    }

    companion object {
        /** 분류기 식별 이름 */
        const val CLASSIFIER_NAME = "rule"
    }
}
