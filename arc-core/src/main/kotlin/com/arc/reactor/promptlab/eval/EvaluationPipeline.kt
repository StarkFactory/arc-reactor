package com.arc.reactor.promptlab.eval

import com.arc.reactor.promptlab.model.EvaluationConfig
import com.arc.reactor.promptlab.model.EvaluationResult
import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.TestQuery
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Orchestrates the 3-tier evaluation pipeline with fail-fast.
 *
 * Tiers execute sequentially: Structural -> Rules -> LLM Judge.
 * If a tier fails, subsequent tiers are skipped.
 */
class EvaluationPipeline(
    private val structural: StructuralEvaluator,
    private val rules: RuleBasedEvaluator,
    private val llmJudge: LlmJudgeEvaluator?,
    private val config: EvaluationConfig
) {

    suspend fun evaluate(
        response: String,
        query: TestQuery
    ): List<EvaluationResult> {
        val results = mutableListOf<EvaluationResult>()

        if (config.structuralEnabled) {
            val result = evaluateSafe(EvaluationTier.STRUCTURAL) {
                structural.evaluate(response, query)
            }
            results.add(result)
            if (!result.passed) {
                logger.debug { "Structural tier failed, skipping remaining" }
                return results
            }
        }

        if (config.rulesEnabled) {
            val result = evaluateSafe(EvaluationTier.RULES) {
                rules.evaluate(response, query)
            }
            results.add(result)
            if (!result.passed) {
                logger.debug { "Rules tier failed, skipping LLM judge" }
                return results
            }
        }

        if (config.llmJudgeEnabled && llmJudge != null) {
            val result = evaluateSafe(EvaluationTier.LLM_JUDGE) {
                llmJudge.evaluate(response, query, config)
            }
            results.add(result)
        }

        return results
    }

    private suspend fun evaluateSafe(
        tier: EvaluationTier,
        block: suspend () -> EvaluationResult
    ): EvaluationResult {
        return try {
            block()
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Evaluator exception in tier=$tier" }
            EvaluationResult(
                tier = tier,
                passed = false,
                score = 0.0,
                reason = "Evaluator error: ${e.message}"
            )
        }
    }
}

/**
 * Factory for creating EvaluationPipeline instances with varying configs.
 */
class EvaluationPipelineFactory(
    private val structural: StructuralEvaluator,
    private val rules: RuleBasedEvaluator,
    private val llmJudge: LlmJudgeEvaluator?
) {

    fun create(config: EvaluationConfig): EvaluationPipeline {
        return EvaluationPipeline(structural, rules, llmJudge, config)
    }
}
