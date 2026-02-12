package com.arc.reactor.intent.impl

import com.arc.reactor.intent.IntentClassifier
import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.ClassifiedIntent
import com.arc.reactor.intent.model.IntentResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Rule-Based Intent Classifier
 *
 * Matches user input against intent keywords using case-insensitive containment.
 * Zero token cost — no LLM calls. Suitable for high-confidence patterns only.
 *
 * ## Design Decisions
 * - Confidence = matchedKeywords / totalKeywords (e.g. 2/4 = 0.5)
 * - Returns [IntentResult.unknown] when no keywords match — caller should fallback to LLM
 * - Does NOT handle negation — by design,
 *   only register keywords for unambiguous patterns (greetings, commands)
 *
 * @param registry Source of intent definitions with keywords
 */
class RuleBasedIntentClassifier(
    private val registry: IntentRegistry
) : IntentClassifier {

    override suspend fun classify(text: String, context: ClassificationContext): IntentResult {
        val startTime = System.nanoTime()
        val normalizedText = text.lowercase().trim()
        val matches = mutableListOf<ClassifiedIntent>()

        for (intent in registry.listEnabled()) {
            if (intent.keywords.isEmpty()) continue

            val matchCount = intent.keywords.count { keyword ->
                normalizedText.contains(keyword.lowercase())
            }

            if (matchCount > 0) {
                val confidence = (matchCount.toDouble() / intent.keywords.size).coerceAtMost(1.0)
                matches.add(ClassifiedIntent(intentName = intent.name, confidence = confidence))
            }
        }

        val latencyMs = (System.nanoTime() - startTime) / 1_000_000

        if (matches.isEmpty()) {
            logger.debug { "Rule-based: no keyword match for input (length=${text.length})" }
            return IntentResult.unknown(classifiedBy = CLASSIFIER_NAME, latencyMs = latencyMs)
        }

        val sorted = matches.sortedByDescending { it.confidence }
        val result = IntentResult(
            primary = sorted.first(),
            secondary = sorted.drop(1),
            classifiedBy = CLASSIFIER_NAME,
            tokenCost = 0,
            latencyMs = latencyMs
        )

        logger.debug {
            "Rule-based: matched intent=${result.primary?.intentName} " +
                "confidence=${result.primary?.confidence} alternatives=${sorted.size - 1}"
        }
        return result
    }

    companion object {
        const val CLASSIFIER_NAME = "rule"
    }
}
