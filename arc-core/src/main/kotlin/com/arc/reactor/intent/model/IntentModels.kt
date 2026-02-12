package com.arc.reactor.intent.model

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.ResponseFormat
import java.time.Instant

/**
 * Intent definition — describes a type of user request the system can handle.
 *
 * Each intent has a [profile] that overrides the default agent configuration
 * when a request is classified as this intent.
 *
 * @param name Unique identifier (e.g. "greeting", "order_inquiry", "data_analysis")
 * @param description Human-readable description used by LLM classifier for intent selection
 * @param examples Few-shot examples for LLM classification accuracy
 * @param keywords Keywords for rule-based classification (high-confidence patterns only)
 * @param profile Pipeline configuration overrides for this intent
 * @param enabled Whether this intent is active for classification
 * @param createdAt Creation timestamp
 * @param updatedAt Last modification timestamp
 */
data class IntentDefinition(
    val name: String,
    val description: String,
    val examples: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val profile: IntentProfile = IntentProfile(),
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * Intent profile — pipeline configuration overrides applied when an intent is matched.
 *
 * All fields are nullable. `null` means "use the global default".
 * Only non-null values override the default agent configuration.
 *
 * @param model LLM provider override (e.g. "gemini", "openai", "anthropic")
 * @param temperature Temperature override
 * @param maxToolCalls Maximum tool calls override
 * @param allowedTools Tool whitelist (null = all tools allowed)
 * @param systemPrompt System prompt override
 * @param responseFormat Response format override
 */
data class IntentProfile(
    val model: String? = null,
    val temperature: Double? = null,
    val maxToolCalls: Int? = null,
    val allowedTools: Set<String>? = null,
    val systemPrompt: String? = null,
    val responseFormat: ResponseFormat? = null
)

/**
 * Classification context — additional information for intent classification.
 *
 * @param userId User identifier for personalized classification
 * @param conversationHistory Recent conversation history for context-dependent classification
 * @param channel Request channel (e.g. "web", "mobile", "api")
 * @param metadata Additional metadata for custom classifiers
 */
data class ClassificationContext(
    val userId: String? = null,
    val conversationHistory: List<Message> = emptyList(),
    val channel: String? = null,
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        val EMPTY = ClassificationContext()
    }
}

/**
 * Intent classification result.
 *
 * @param primary Primary classified intent (null = unknown/no match)
 * @param secondary Additional intents detected (for multi-intent inputs)
 * @param classifiedBy Classifier that produced this result ("rule" or "llm")
 * @param tokenCost Tokens consumed by classification (0 for rule-based)
 * @param latencyMs Classification latency in milliseconds
 */
data class IntentResult(
    val primary: ClassifiedIntent?,
    val secondary: List<ClassifiedIntent> = emptyList(),
    val classifiedBy: String,
    val tokenCost: Int = 0,
    val latencyMs: Long = 0
) {
    /** Whether no intent was matched */
    val isUnknown: Boolean get() = primary == null

    companion object {
        /** Create an unknown result (no intent matched) */
        fun unknown(classifiedBy: String, tokenCost: Int = 0, latencyMs: Long = 0) =
            IntentResult(
                primary = null,
                classifiedBy = classifiedBy,
                tokenCost = tokenCost,
                latencyMs = latencyMs
            )
    }
}

/**
 * A single classified intent with confidence score.
 *
 * @param intentName Name of the matched intent
 * @param confidence Confidence score (0.0 to 1.0)
 */
data class ClassifiedIntent(
    val intentName: String,
    val confidence: Double
) {
    init {
        require(confidence in 0.0..1.0) { "Confidence must be between 0.0 and 1.0, got $confidence" }
    }
}
