package com.arc.reactor.cache

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry

/**
 * 캐시 히트율 및 비용 절감 추정 메트릭을 기록하는 인터페이스.
 *
 * 정확 일치(exact)와 시맨틱(semantic) 캐시 히트를 구분하여 기록하고,
 * 캐시 히트로 인한 LLM 호출 비용 절감을 추정한다.
 *
 * @see NoOpCacheMetricsRecorder 비활성화(no-op) 구현체
 * @see MicrometerCacheMetricsRecorder Micrometer 기반 구현체
 */
interface CacheMetricsRecorder {

    /** 정확 일치 캐시 히트를 기록한다. */
    fun recordExactHit()

    /**
     * 시맨틱 캐시 히트를 유사도 점수와 함께 기록한다.
     *
     * @param similarityScore 임베딩 유사도 점수 (0.0 ~ 1.0)
     */
    fun recordSemanticHit(similarityScore: Double)

    /** 캐시 미스를 기록한다. */
    fun recordMiss()

    /**
     * 캐시 히트로 절감된 LLM 호출 비용 추정치를 기록한다.
     *
     * @param model LLM 모델 이름
     * @param estimatedInputTokens 절감된 추정 입력 토큰 수
     * @param estimatedOutputTokens 절감된 추정 출력 토큰 수
     */
    fun recordEstimatedCostSaved(model: String, estimatedInputTokens: Int, estimatedOutputTokens: Int)
}

/**
 * No-op 캐시 메트릭 구현체 (기본값).
 *
 * Micrometer가 없거나 메트릭 수집이 불필요한 환경에서 사용된다.
 */
class NoOpCacheMetricsRecorder : CacheMetricsRecorder {
    override fun recordExactHit() {}
    override fun recordSemanticHit(similarityScore: Double) {}
    override fun recordMiss() {}
    override fun recordEstimatedCostSaved(model: String, estimatedInputTokens: Int, estimatedOutputTokens: Int) {}
}

/**
 * Micrometer 기반 [CacheMetricsRecorder] 구현체.
 *
 * 다음 메트릭을 [MeterRegistry]에 기록한다:
 * - `arc.cache.hits` (counter, tag: type=exact|semantic) — 캐시 히트 횟수
 * - `arc.cache.misses` (counter) — 캐시 미스 횟수
 * - `arc.cache.semantic.similarity` (summary) — 시맨틱 유사도 점수 분포
 * - `arc.cache.cost.saved.estimate` (counter) — 절감 추정 비용 (USD)
 *
 * @param registry Micrometer 메트릭 레지스트리
 */
internal class MicrometerCacheMetricsRecorder(
    private val registry: MeterRegistry
) : CacheMetricsRecorder {

    private val exactHitCounter: Counter = Counter.builder(METRIC_CACHE_HITS)
        .tag("type", "exact")
        .description("Cache hits by type")
        .register(registry)

    private val semanticHitCounter: Counter = Counter.builder(METRIC_CACHE_HITS)
        .tag("type", "semantic")
        .description("Cache hits by type")
        .register(registry)

    private val missCounter: Counter = Counter.builder(METRIC_CACHE_MISSES)
        .description("Cache misses")
        .register(registry)

    private val similaritySummary: DistributionSummary = DistributionSummary.builder(METRIC_SEMANTIC_SIMILARITY)
        .description("Semantic cache hit similarity score distribution")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry)

    override fun recordExactHit() {
        exactHitCounter.increment()
    }

    override fun recordSemanticHit(similarityScore: Double) {
        semanticHitCounter.increment()
        similaritySummary.record(similarityScore)
    }

    override fun recordMiss() {
        missCounter.increment()
    }

    override fun recordEstimatedCostSaved(model: String, estimatedInputTokens: Int, estimatedOutputTokens: Int) {
        val costUsd = estimateCostUsd(model, estimatedInputTokens, estimatedOutputTokens)
        if (costUsd <= 0.0) return
        Counter.builder(METRIC_COST_SAVED)
            .tag("model", model)
            .description("Estimated cost saved by cache hits (USD)")
            .register(registry)
            .increment(costUsd)
    }

    companion object {
        private const val METRIC_CACHE_HITS = "arc.cache.hits"
        private const val METRIC_CACHE_MISSES = "arc.cache.misses"
        private const val METRIC_SEMANTIC_SIMILARITY = "arc.cache.semantic.similarity"
        private const val METRIC_COST_SAVED = "arc.cache.cost.saved.estimate"

        /** 알 수 없는 모델의 보수적 기본 단가 (토큰당 USD). */
        private val DEFAULT_PRICING: Pair<Double, Double> = 0.001 / 1000 to 0.002 / 1000

        /**
         * 모델별 토큰당 USD 단가 (입력, 출력).
         * 가장 긴 키부터 매칭하여 "gpt-4o-mini"가 "gpt-4o"보다 먼저 매칭되도록 정렬.
         */
        private val MODEL_PRICING: List<Pair<String, Pair<Double, Double>>> = listOf(
            "gpt-4o-mini" to (0.00015 / 1000 to 0.0006 / 1000),
            "gpt-4o" to (0.0025 / 1000 to 0.01 / 1000),
            "gpt-4" to (0.03 / 1000 to 0.06 / 1000),
            "gpt-3.5-turbo" to (0.0005 / 1000 to 0.0015 / 1000),
            "claude-3-opus" to (0.015 / 1000 to 0.075 / 1000),
            "claude-3-sonnet" to (0.003 / 1000 to 0.015 / 1000),
            "claude-3-haiku" to (0.00025 / 1000 to 0.00125 / 1000),
            "gemini-1.5-pro" to (0.00125 / 1000 to 0.005 / 1000),
            "gemini-1.5-flash" to (0.000075 / 1000 to 0.0003 / 1000),
            "gemini-pro" to (0.00025 / 1000 to 0.0005 / 1000)
        ).sortedByDescending { it.first.length }

        /** 모델별 단가로 USD 비용을 추정한다. 가장 긴 키부터 매칭하여 정확도를 높인다. */
        internal fun estimateCostUsd(model: String, inputTokens: Int, outputTokens: Int): Double {
            val normalizedModel = model.lowercase()
            val (inputRate, outputRate) = MODEL_PRICING
                .firstOrNull { normalizedModel.contains(it.first) }
                ?.second ?: DEFAULT_PRICING
            return inputTokens * inputRate + outputTokens * outputRate
        }
    }
}
