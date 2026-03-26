package com.arc.reactor.admin.pricing

import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 모델 가격 테이블 기반 토큰 비용 계산기 (청구/분석용).
 *
 * Caffeine 캐시(5분 TTL)로 가격 조회를 최적화한다.
 * uncached prompt, cached input, completion, reasoning 토큰을 구분하여 비용을 산출한다.
 *
 * ## arc-core CostCalculator와의 관계
 * arc-core 모듈에도 동명의 CostCalculator가 존재하지만 설계 목적이 다르다:
 * - **이 클래스 (arc-admin)**: DB 기반 가격표, BigDecimal 정밀도,
 *   토큰 티어 구분 (cached/reasoning). 메트릭 기록 및 청구에 사용. 정밀도 우선.
 * - **arc-core CostCalculator**: 하드코딩 가격표, Double 정밀도, I/O 없음.
 *   ReAct 루프 내 실시간 예산 판단에 사용. 속도 우선.
 *
 * 이 분리는 의도적이다 — 에이전트 실행 핫 패스에서 DB 호출을 피하면서도
 * 청구 정확도를 보장하기 위함.
 *
 * @see ModelPricingStore 가격 정보 저장소
 * @see MetricWriter writer 스레드에서 TokenUsageEvent 비용 보강 시 사용
 */
class CostCalculator(private val pricingStore: ModelPricingStore) {

    private val pricingCache = Caffeine.newBuilder()
        .maximumSize(200)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build<String, ModelPricing>()

    /** 지정된 토큰 수에 대한 추정 비용(USD)을 계산한다. 가격 정보가 없으면 0을 반환한다. */
    fun calculate(
        provider: String,
        model: String,
        time: Instant,
        promptTokens: Int,
        cachedTokens: Int = 0,
        completionTokens: Int,
        reasoningTokens: Int = 0
    ): BigDecimal {
        val pricing = findPricing(provider, model, time)
        if (pricing == null) {
            logger.debug { "No pricing found for $provider:$model at $time, returning zero cost" }
            return BigDecimal.ZERO
        }

        val uncachedPrompt = (promptTokens - cachedTokens).coerceAtLeast(0)
        val cost = pricePer(uncachedPrompt, pricing.promptPricePer1k) +
            pricePer(cachedTokens, pricing.cachedInputPricePer1k) +
            pricePer(completionTokens, pricing.completionPricePer1k) +
            pricePer(reasoningTokens, pricing.reasoningPricePer1k)

        return cost.setScale(8, RoundingMode.HALF_UP)
    }

    private fun findPricing(provider: String, model: String, time: Instant): ModelPricing? {
        val key = "$provider:$model:${time.epochSecond / 300}"
        return pricingCache.getIfPresent(key) ?: pricingStore.findEffective(provider, model, time)?.also {
            pricingCache.put(key, it)
        }
    }

    private fun pricePer(tokens: Int, pricePer1k: BigDecimal): BigDecimal {
        if (tokens <= 0) return BigDecimal.ZERO
        return BigDecimal(tokens).multiply(pricePer1k).divide(THOUSAND, 8, RoundingMode.HALF_UP)
    }

    companion object {
        private val THOUSAND = BigDecimal(1000)
    }
}
