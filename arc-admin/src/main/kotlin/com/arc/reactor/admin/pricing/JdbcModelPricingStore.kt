package com.arc.reactor.admin.pricing

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

class JdbcModelPricingStore(
    private val jdbcTemplate: JdbcTemplate
) : ModelPricingStore {

    override fun findEffective(provider: String, model: String, at: Instant): ModelPricing? {
        val results = jdbcTemplate.query(
            """SELECT * FROM model_pricing
               WHERE provider = ? AND model = ?
                 AND effective_from <= ?
                 AND (effective_to IS NULL OR effective_to > ?)
               ORDER BY effective_from DESC
               LIMIT 1""",
            ROW_MAPPER,
            provider, model, Timestamp.from(at), Timestamp.from(at)
        )
        return results.firstOrNull()
    }

    override fun findAll(): List<ModelPricing> {
        return jdbcTemplate.query(
            "SELECT * FROM model_pricing ORDER BY effective_from DESC",
            ROW_MAPPER
        )
    }

    override fun save(pricing: ModelPricing): ModelPricing {
        jdbcTemplate.update(
            """INSERT INTO model_pricing (id, provider, model,
                   prompt_price_per_1k, completion_price_per_1k,
                   cached_input_price_per_1k, reasoning_price_per_1k,
                   batch_prompt_price_per_1k, batch_completion_price_per_1k,
                   effective_from, effective_to)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (id) DO UPDATE SET
                   provider = EXCLUDED.provider, model = EXCLUDED.model,
                   prompt_price_per_1k = EXCLUDED.prompt_price_per_1k,
                   completion_price_per_1k = EXCLUDED.completion_price_per_1k,
                   cached_input_price_per_1k = EXCLUDED.cached_input_price_per_1k,
                   reasoning_price_per_1k = EXCLUDED.reasoning_price_per_1k,
                   batch_prompt_price_per_1k = EXCLUDED.batch_prompt_price_per_1k,
                   batch_completion_price_per_1k = EXCLUDED.batch_completion_price_per_1k,
                   effective_from = EXCLUDED.effective_from, effective_to = EXCLUDED.effective_to""",
            pricing.id, pricing.provider, pricing.model,
            pricing.promptPricePer1k, pricing.completionPricePer1k,
            pricing.cachedInputPricePer1k, pricing.reasoningPricePer1k,
            pricing.batchPromptPricePer1k, pricing.batchCompletionPricePer1k,
            Timestamp.from(pricing.effectiveFrom),
            pricing.effectiveTo?.let { Timestamp.from(it) }
        )
        return pricing
    }

    override fun delete(id: String): Boolean {
        val count = jdbcTemplate.update("DELETE FROM model_pricing WHERE id = ?", id)
        return count > 0
    }

    companion object {
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            ModelPricing(
                id = rs.getString("id"),
                provider = rs.getString("provider"),
                model = rs.getString("model"),
                promptPricePer1k = rs.getBigDecimal("prompt_price_per_1k"),
                completionPricePer1k = rs.getBigDecimal("completion_price_per_1k"),
                cachedInputPricePer1k = rs.getBigDecimal("cached_input_price_per_1k"),
                reasoningPricePer1k = rs.getBigDecimal("reasoning_price_per_1k"),
                batchPromptPricePer1k = rs.getBigDecimal("batch_prompt_price_per_1k"),
                batchCompletionPricePer1k = rs.getBigDecimal("batch_completion_price_per_1k"),
                effectiveFrom = rs.getTimestamp("effective_from").toInstant(),
                effectiveTo = rs.getTimestamp("effective_to")?.toInstant()
            )
        }
    }
}
