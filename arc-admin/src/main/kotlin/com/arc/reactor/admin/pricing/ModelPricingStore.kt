package com.arc.reactor.admin.pricing

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 모델 가격 정보. provider/model별 토큰당 가격과 유효 기간을 포함한다. */
data class ModelPricing(
    val id: String = UUID.randomUUID().toString(),
    val provider: String,
    val model: String,
    val promptPricePer1k: BigDecimal = BigDecimal.ZERO,
    val completionPricePer1k: BigDecimal = BigDecimal.ZERO,
    val cachedInputPricePer1k: BigDecimal = BigDecimal.ZERO,
    val reasoningPricePer1k: BigDecimal = BigDecimal.ZERO,
    val batchPromptPricePer1k: BigDecimal = BigDecimal.ZERO,
    val batchCompletionPricePer1k: BigDecimal = BigDecimal.ZERO,
    val effectiveFrom: Instant = Instant.now(),
    val effectiveTo: Instant? = null
)

/**
 * 모델 가격 정보 저장소 인터페이스.
 *
 * @see JdbcModelPricingStore JDBC 기반 구현
 * @see InMemoryModelPricingStore 인메모리 구현
 */
interface ModelPricingStore {
    fun findEffective(provider: String, model: String, at: Instant): ModelPricing?
    fun findAll(): List<ModelPricing>
    fun save(pricing: ModelPricing): ModelPricing
    fun delete(id: String): Boolean
}

/** ConcurrentHashMap 기반 인메모리 [ModelPricingStore] 구현체. */
class InMemoryModelPricingStore : ModelPricingStore {
    private val pricings = ConcurrentHashMap<String, ModelPricing>()

    override fun findEffective(provider: String, model: String, at: Instant): ModelPricing? =
        pricings.values
            .filter { it.provider == provider && it.model == model }
            .filter { it.effectiveFrom <= at && (it.effectiveTo == null || it.effectiveTo > at) }
            .maxByOrNull { it.effectiveFrom }

    override fun findAll(): List<ModelPricing> =
        pricings.values.sortedByDescending { it.effectiveFrom }

    override fun save(pricing: ModelPricing): ModelPricing {
        pricings[pricing.id] = pricing
        return pricing
    }

    override fun delete(id: String): Boolean = pricings.remove(id) != null
}
