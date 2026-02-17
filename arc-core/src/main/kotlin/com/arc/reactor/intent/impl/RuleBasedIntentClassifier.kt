package com.arc.reactor.intent.impl

import com.arc.reactor.intent.IntentClassifier
import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.ClassifiedIntent
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Rule-Based Intent Classifier
 *
 * Matches user input against intent keywords using case-insensitive containment.
 * Zero token cost — no LLM calls. Suitable for high-confidence patterns only.
 *
 * ## Enhanced Features
 * - **Synonyms**: Each keyword can have synonyms; matching any synonym counts as one match
 * - **Weights**: Keywords can have custom weights (default 1.0) affecting confidence
 * - **Negative keywords**: If any negative keyword matches, the intent is excluded
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
            val scored = scoreIntent(intent, normalizedText) ?: continue
            matches.add(scored)
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

    /**
     * Score a single intent against the normalized input text.
     *
     * @return ClassifiedIntent if at least one keyword matches, null if excluded or no match
     */
    private fun scoreIntent(intent: IntentDefinition, normalizedText: String): ClassifiedIntent? {
        // 1. Negative keywords — any match excludes this intent immediately
        for (neg in intent.negativeKeywords) {
            if (normalizedText.contains(neg.lowercase())) return null
        }

        // 2. Score each keyword (with synonyms and weights)
        var matchedWeight = 0.0
        var totalWeight = 0.0

        for (keyword in intent.keywords) {
            val weight = intent.keywordWeights[keyword] ?: 1.0
            totalWeight += weight
            val variants = buildEffectiveKeywords(keyword, intent.synonyms)
            if (variants.any { normalizedText.contains(it.lowercase()) }) {
                matchedWeight += weight
            }
        }

        if (matchedWeight <= 0.0) return null
        val confidence = (matchedWeight / totalWeight).coerceAtMost(1.0)
        return ClassifiedIntent(intentName = intent.name, confidence = confidence)
    }

    /**
     * Build the set of effective keywords: the original keyword + its synonyms.
     */
    private fun buildEffectiveKeywords(
        keyword: String,
        synonyms: Map<String, List<String>>
    ): List<String> {
        val syns = synonyms[keyword]
        return if (syns.isNullOrEmpty()) listOf(keyword) else listOf(keyword) + syns
    }

    companion object {
        const val CLASSIFIER_NAME = "rule"
    }
}
