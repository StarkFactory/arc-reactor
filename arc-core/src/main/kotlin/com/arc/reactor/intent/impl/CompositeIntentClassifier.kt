package com.arc.reactor.intent.impl

import com.arc.reactor.intent.IntentClassifier
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.IntentResult
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Composite Intent Classifier (Rule -> LLM Cascading)
 *
 * Two-stage classification strategy:
 * 1. **Rule-based** — zero token cost, handles high-confidence keyword patterns
 * 2. **LLM-based** — fallback for ambiguous inputs requiring semantic understanding
 *
 * The LLM classifier is only invoked when:
 * - Rule-based returns unknown (no keyword match), OR
 * - Rule-based confidence is below [ruleConfidenceThreshold]
 *
 * ## Token Savings
 * In production traffic, rule-based typically handles 10-20% of requests at zero cost.
 * The remaining 80-90% fall through to LLM (~200-500 tokens each).
 *
 * @param ruleClassifier Rule-based classifier (first stage)
 * @param llmClassifier LLM-based classifier (fallback stage)
 * @param ruleConfidenceThreshold Minimum rule-based confidence to skip LLM (default 0.8)
 */
class CompositeIntentClassifier(
    private val ruleClassifier: IntentClassifier,
    private val llmClassifier: IntentClassifier,
    private val ruleConfidenceThreshold: Double = 0.8
) : IntentClassifier {

    override suspend fun classify(text: String, context: ClassificationContext): IntentResult {
        val ruleResult = ruleClassifier.classify(text, context)

        if (!ruleResult.isUnknown && ruleResult.primary!!.confidence >= ruleConfidenceThreshold) {
            logger.debug {
                "Composite: rule-based match accepted " +
                    "(intent=${ruleResult.primary.intentName}, confidence=${ruleResult.primary.confidence})"
            }
            return ruleResult
        }

        try {
            val llmResult = llmClassifier.classify(text, context)

            logger.debug {
                val ruleInfo = if (ruleResult.isUnknown) "no rule match"
                else "rule=${ruleResult.primary!!.intentName}(${ruleResult.primary.confidence})"
                "Composite: LLM fallback used ($ruleInfo) -> " +
                    "llm=${llmResult.primary?.intentName}(${llmResult.primary?.confidence})"
            }

            return llmResult
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Composite: LLM classification failed, falling back to rule result" }
            return ruleResult
        }
    }
}
