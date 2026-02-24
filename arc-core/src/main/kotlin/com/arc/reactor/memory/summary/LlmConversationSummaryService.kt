package com.arc.reactor.memory.summary

import com.arc.reactor.agent.model.Message
import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runInterruptible
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * LLM-based conversation summarization service.
 *
 * Extracts structured facts and a narrative summary from conversation
 * messages in a single LLM call. Always summarizes from original messages
 * (no summary-of-summary).
 *
 * @param chatClient ChatClient configured for the summarization model
 * @param maxNarrativeTokens Approximate token budget for the narrative
 */
class LlmConversationSummaryService(
    private val chatClient: ChatClient,
    private val maxNarrativeTokens: Int = 500
) : ConversationSummaryService {

    private val objectMapper = jacksonObjectMapper()

    override suspend fun summarize(
        messages: List<Message>,
        existingFacts: List<StructuredFact>
    ): SummarizationResult {
        if (messages.isEmpty()) {
            return SummarizationResult(narrative = "", facts = emptyList())
        }

        val conversationText = formatMessages(messages)
        val existingFactsText = formatExistingFacts(existingFacts)
        val userPrompt = buildUserPrompt(conversationText, existingFactsText)

        val responseText = callLlm(userPrompt)
        return parseResponse(responseText)
    }

    private fun formatMessages(messages: List<Message>): String {
        return messages.joinToString("\n") { msg ->
            "[${msg.role.name}]: ${msg.content}"
        }
    }

    private fun formatExistingFacts(facts: List<StructuredFact>): String {
        if (facts.isEmpty()) return ""
        return "\n\nPreviously extracted facts (merge and update as needed):\n" +
            facts.joinToString("\n") { "- ${it.key}: ${it.value} [${it.category}]" }
    }

    private fun buildUserPrompt(conversationText: String, existingFactsText: String): String {
        return """
            |$conversationText
            |$existingFactsText
        """.trimMargin().trim()
    }

    private suspend fun callLlm(userPrompt: String): String {
        val response = runInterruptible {
            chatClient.prompt()
                .system(SYSTEM_PROMPT.format(maxNarrativeTokens))
                .user(userPrompt)
                .call()
                .chatResponse()
        }
        return response?.result?.output?.text.orEmpty()
    }

    internal fun parseResponse(responseText: String): SummarizationResult {
        val cleaned = stripCodeFences(responseText)
        return try {
            val parsed = objectMapper.readValue<SummaryJsonResponse>(cleaned)
            SummarizationResult(
                narrative = parsed.narrative.orEmpty().trim(),
                facts = parsed.facts.orEmpty().map { it.toStructuredFact() }
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to parse summarization response, using raw text as narrative" }
            SummarizationResult(narrative = cleaned.trim(), facts = emptyList())
        }
    }

    companion object {
        internal val SYSTEM_PROMPT = """
            You are a conversation summarizer. Analyze the conversation and produce a JSON response with two fields:

            1. "narrative": A concise summary of the conversation flow, tone, and key points. Maximum ~%d tokens.
               Focus on: what was discussed, decisions made, emotional tone, and unresolved items.

            2. "facts": An array of structured facts extracted from the conversation. Each fact has:
               - "key": A short identifier (e.g., "order_number", "customer_name", "agreed_price")
               - "value": The exact value from the conversation
               - "category": One of ENTITY, DECISION, CONDITION, STATE, NUMERIC, GENERAL

            Rules:
            - Extract ALL numbers, dates, names, and specific conditions as facts
            - Preserve exact values (don't paraphrase numbers or identifiers)
            - If existing facts are provided, merge them: update changed values, keep unchanged ones, add new ones
            - Respond ONLY with valid JSON, no markdown code fences

            Example response:
            {
              "narrative": "Customer inquired about refund for order #1234. Agent confirmed eligibility and initiated refund of 50,000 KRW. Customer expressed satisfaction.",
              "facts": [
                {"key": "order_number", "value": "#1234", "category": "ENTITY"},
                {"key": "refund_amount", "value": "50,000 KRW", "category": "NUMERIC"},
                {"key": "refund_status", "value": "initiated", "category": "STATE"}
              ]
            }
        """.trimIndent()

        private val CODE_FENCE_PATTERN = Regex("""^```(?:json)?\s*\n?(.*?)\n?\s*```$""", RegexOption.DOT_MATCHES_ALL)

        internal fun stripCodeFences(text: String): String {
            val trimmed = text.trim()
            val match = CODE_FENCE_PATTERN.find(trimmed)
            return match?.groupValues?.get(1)?.trim() ?: trimmed
        }
    }
}

internal data class SummaryJsonResponse(
    val narrative: String? = null,
    val facts: List<FactJson>? = null
)

internal data class FactJson(
    val key: String = "",
    val value: String = "",
    val category: String = "GENERAL"
) {
    fun toStructuredFact(): StructuredFact = StructuredFact(
        key = key,
        value = value,
        category = try {
            FactCategory.valueOf(category.uppercase())
        } catch (_: IllegalArgumentException) {
            FactCategory.GENERAL
        }
    )
}
