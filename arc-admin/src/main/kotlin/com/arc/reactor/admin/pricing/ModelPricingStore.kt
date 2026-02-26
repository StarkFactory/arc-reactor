package com.arc.reactor.admin.pricing

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

interface ModelPricingStore {
    fun findEffective(provider: String, model: String, at: Instant): ModelPricing?
    fun findAll(): List<ModelPricing>
    fun save(pricing: ModelPricing): ModelPricing
    fun delete(id: String): Boolean
}

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
