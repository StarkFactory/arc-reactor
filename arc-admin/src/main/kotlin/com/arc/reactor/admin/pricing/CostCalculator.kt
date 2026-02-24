package com.arc.reactor.admin.pricing

import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

class CostCalculator(private val pricingStore: ModelPricingStore) {

    private val pricingCache = Caffeine.newBuilder()
        .maximumSize(200)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build<String, ModelPricing>()

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
