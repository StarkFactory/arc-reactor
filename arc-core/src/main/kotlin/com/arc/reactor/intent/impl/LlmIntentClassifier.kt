package com.arc.reactor.intent.impl

import com.arc.reactor.intent.IntentClassifier
import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.ClassifiedIntent
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentResult
import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatResponse

private val logger = KotlinLogging.logger {}

/**
 * LLM-Based Intent Classifier
 *
 * Uses a small/fast LLM to classify user input into registered intents.
 * Optimized for minimal token consumption (~200-500 tokens per classification).
 *
 * ## Token Optimization Strategy
 * - Compact system prompt (no verbose instructions)
 * - Intent descriptions only (no full system prompts)
 * - Max 3 examples per intent in the classification prompt
 * - Recent 2 conversation turns for context (4 messages max)
 * - JSON-only response format for minimal output tokens
 *
 * ## Error Handling
 * Returns [IntentResult.unknown] on any LLM error — never blocks the main pipeline.
 *
 * @param chatClient ChatClient for the classification LLM
 * @param registry Source of intent definitions
 * @param maxExamplesPerIntent Maximum few-shot examples per intent in prompt
 * @param maxConversationTurns Maximum conversation turns to include for context
 */
class LlmIntentClassifier(
    private val chatClient: ChatClient,
    private val registry: IntentRegistry,
    private val maxExamplesPerIntent: Int = 3,
    private val maxConversationTurns: Int = 2
) : IntentClassifier {

    override suspend fun classify(text: String, context: ClassificationContext): IntentResult {
        val startTime = System.nanoTime()
        val enabledIntents = registry.listEnabled()

        if (enabledIntents.isEmpty()) {
            logger.debug { "LLM classifier: no enabled intents registered" }
            return IntentResult.unknown(classifiedBy = CLASSIFIER_NAME)
        }

        try {
            val prompt = buildClassificationPrompt(text, enabledIntents, context)
            val response = callLlm(prompt)
            val parsed = parseResponse(response)
            val latencyMs = (System.nanoTime() - startTime) / 1_000_000
            val tokenCost = estimateTokenCost(prompt, response)

            if (parsed == null) {
                logger.warn { "LLM classifier: failed to parse response: $response" }
                return IntentResult.unknown(
                    classifiedBy = CLASSIFIER_NAME,
                    tokenCost = tokenCost,
                    latencyMs = latencyMs
                )
            }

            val primary = parsed.first()
            val secondary = parsed.drop(1)

            logger.debug {
                "LLM classifier: intent=${primary.intentName} " +
                    "confidence=${primary.confidence} tokenCost=$tokenCost latencyMs=$latencyMs"
            }

            return IntentResult(
                primary = primary,
                secondary = secondary,
                classifiedBy = CLASSIFIER_NAME,
                tokenCost = tokenCost,
                latencyMs = latencyMs
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            val latencyMs = (System.nanoTime() - startTime) / 1_000_000
            logger.error(e) { "LLM classifier: classification failed, returning unknown" }
            return IntentResult.unknown(
                classifiedBy = CLASSIFIER_NAME,
                latencyMs = latencyMs
            )
        }
    }

    internal fun buildClassificationPrompt(
        text: String,
        intents: List<IntentDefinition>,
        context: ClassificationContext
    ): String {
        val intentDescriptions = intents.joinToString("\n") { intent ->
            val examples = intent.examples.take(maxExamplesPerIntent)
            val exampleLines = if (examples.isNotEmpty()) {
                "\n  Examples:\n" + examples.joinToString("\n") { "    - \"$it\"" }
            } else ""
            "- ${intent.name}: ${intent.description}$exampleLines"
        }

        val conversationContext = buildConversationContext(context)
        val intentNames = intents.joinToString(", ") { "\"${it.name}\"" }

        return buildString {
            appendLine(SYSTEM_INSTRUCTION)
            appendLine()
            appendLine("Intents:")
            appendLine(intentDescriptions)
            if (conversationContext.isNotEmpty()) {
                appendLine()
                appendLine("Recent conversation:")
                appendLine(conversationContext)
            }
            appendLine()
            appendLine("User input: \"$text\"")
            appendLine()
            appendLine("Respond with JSON only: {\"intents\":[{\"name\":\"...\",\"confidence\":0.0-1.0}]}")
            appendLine("Valid intent names: [$intentNames, \"unknown\"]")
        }
    }

    private fun buildConversationContext(context: ClassificationContext): String {
        val history = context.conversationHistory
        if (history.isEmpty()) return ""

        val recentMessages = history.takeLast(maxConversationTurns * 2)
        return recentMessages.joinToString("\n") { msg ->
            "- ${msg.role.name}: ${msg.content.take(200)}"
        }
    }

    private fun callLlm(prompt: String): String {
        val response: ChatResponse? = chatClient
            .prompt()
            .user(prompt)
            .call()
            .chatResponse()

        return response?.result?.output?.text.orEmpty()
    }

    internal fun parseResponse(response: String): List<ClassifiedIntent>? {
        return try {
            val cleaned = response
                .replace(CODE_FENCE_REGEX, "")
                .trim()

            val json = objectMapper.readValue<LlmClassificationResponse>(cleaned)
            val validIntents = json.intents
                .filter { it.name != "unknown" && it.confidence > 0.0 }
                .map { ClassifiedIntent(intentName = it.name, confidence = it.confidence.coerceIn(0.0, 1.0)) }
                .sortedByDescending { it.confidence }

            validIntents.ifEmpty { null }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse LLM classification response" }
            null
        }
    }

    private fun estimateTokenCost(prompt: String, response: String): Int {
        return (prompt.length + response.length) / 4
    }

    private data class LlmClassificationResponse(
        val intents: List<LlmClassifiedIntent> = emptyList()
    )

    private data class LlmClassifiedIntent(
        val name: String,
        val confidence: Double
    )

    companion object {
        const val CLASSIFIER_NAME = "llm"

        private val CODE_FENCE_REGEX = Regex("```(?:json)?\\s*|```")

        private const val SYSTEM_INSTRUCTION =
            "Classify the user's intent. Return JSON only, no explanation."

        private val objectMapper = jacksonObjectMapper()
    }
}
