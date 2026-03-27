package com.arc.reactor.memory.summary

import com.arc.reactor.agent.model.Message
import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * LLM 기반 대화 요약 서비스.
 *
 * 단일 LLM 호출로 대화 메시지에서 구조화된 팩트와 서술형 요약을 추출한다.
 * 항상 원본 메시지에서 요약한다 (요약의 요약은 하지 않음).
 *
 * ## 왜 단일 호출인가?
 * Facts와 Narrative를 별도 호출로 추출하면 비용이 2배가 된다.
 * 단일 호출로 JSON 응답을 받아 파싱하는 것이 비용 효율적이다.
 *
 * @param chatClient 요약 모델용으로 설정된 ChatClient
 * @param maxNarrativeTokens 서술 요약의 대략적 토큰 예산
 */
class LlmConversationSummaryService(
    private val chatClient: ChatClient,
    private val maxNarrativeTokens: Int = 500,
    private val llmCallTimeoutMs: Long = DEFAULT_LLM_CALL_TIMEOUT_MS
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

    /** 메시지 목록을 "[역할]: 내용" 형식의 텍스트로 포맷팅한다. */
    private fun formatMessages(messages: List<Message>): String {
        return messages.joinToString("\n") { msg ->
            "[${msg.role.name}]: ${msg.content}"
        }
    }

    /** 기존 팩트를 LLM에 전달할 텍스트로 포맷팅한다. 병합/갱신 지시를 포함. */
    private fun formatExistingFacts(facts: List<StructuredFact>): String {
        if (facts.isEmpty()) return ""
        return "\n\nPreviously extracted facts (merge and update as needed):\n" +
            facts.joinToString("\n") { "- ${it.key}: ${it.value} [${it.category}]" }
    }

    /** 대화 텍스트와 기존 팩트를 합쳐 사용자 프롬프트를 빌드한다. */
    private fun buildUserPrompt(conversationText: String, existingFactsText: String): String {
        return """
            |$conversationText
            |$existingFactsText
        """.trimMargin().trim()
    }

    /** LLM을 호출하여 요약 텍스트를 가져온다. Dispatchers.IO로 블로킹 호출을 오프로드. */
    private suspend fun callLlm(userPrompt: String): String {
        return try {
            withTimeout(llmCallTimeoutMs) {
                val response = runInterruptible(Dispatchers.IO) {
                    chatClient.prompt()
                        .system(SYSTEM_PROMPT.format(maxNarrativeTokens))
                        .user(userPrompt)
                        .call()
                        .chatResponse()
                }
                response?.result?.output?.text.orEmpty()
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "LLM summarization call timed out after ${llmCallTimeoutMs}ms" }
            ""
        }
    }

    /**
     * LLM 응답을 파싱하여 SummarizationResult로 변환한다.
     * JSON 파싱에 실패하면 원본 텍스트를 narrative로 사용한다 (우아한 성능 저하).
     */
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
        /** LLM 요약 호출 기본 타임아웃 (밀리초) */
        private const val DEFAULT_LLM_CALL_TIMEOUT_MS = 60_000L

        /** LLM에 전달하는 요약 시스템 프롬프트 */
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

        /** 코드 펜스(```json ... ```)를 제거하는 정규식 */
        private val CODE_FENCE_PATTERN = Regex("""^```(?:json)?\s*\n?(.*?)\n?\s*```$""", RegexOption.DOT_MATCHES_ALL)

        /** LLM 응답에서 코드 펜스를 제거한다. LLM이 종종 JSON을 코드 블록으로 감싼다. */
        internal fun stripCodeFences(text: String): String {
            val trimmed = text.trim()
            val match = CODE_FENCE_PATTERN.find(trimmed)
            return match?.groupValues?.get(1)?.trim() ?: trimmed
        }
    }
}

/** LLM 응답의 JSON 구조에 매핑되는 내부 데이터 클래스 */
internal data class SummaryJsonResponse(
    val narrative: String? = null,
    val facts: List<FactJson>? = null
)

/** 개별 팩트의 JSON 표현. StructuredFact로 변환 가능. */
internal data class FactJson(
    val key: String = "",
    val value: String = "",
    val category: String = "GENERAL"
) {
    /** FactCategory 열거형으로 변환. 알 수 없는 카테고리는 GENERAL로 폴백. */
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
