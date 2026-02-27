package com.arc.reactor.guard.impl

import com.arc.reactor.guard.ClassificationStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Composite Classification Stage
 *
 * Rule-based first → if allowed and LLM enabled → LLM fallback.
 * Mirrors CompositeIntentClassifier pattern.
 */
class CompositeClassificationStage(
    private val ruleBasedStage: RuleBasedClassificationStage,
    private val llmStage: LlmClassificationStage? = null
) : ClassificationStage {

    override val stageName = "Classification"

    override suspend fun check(command: GuardCommand): GuardResult {
        // Rule-based check first (zero LLM cost)
        val ruleResult = ruleBasedStage.check(command)

        if (ruleResult is GuardResult.Rejected) {
            logger.debug { "Rule-based classification rejected: ${ruleResult.reason}" }
            return ruleResult
        }

        // If LLM is enabled and rule-based passed, try LLM fallback
        if (llmStage != null) {
            logger.debug { "Rule-based passed, trying LLM classification fallback" }
            return llmStage.check(command)
        }

        return ruleResult
    }
}
