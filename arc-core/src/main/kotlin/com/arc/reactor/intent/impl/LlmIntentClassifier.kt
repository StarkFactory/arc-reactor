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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatResponse

private val logger = KotlinLogging.logger {}

/**
 * LLM 기반 인텐트 분류기
 *
 * 소형/빠른 LLM을 사용하여 사용자 입력을 등록된 인텐트로 분류한다.
 * 최소 토큰 소비에 최적화 (분류당 약 200-500 토큰).
 *
 * ## 토큰 최적화 전략
 * - 간결한 시스템 프롬프트 (장황한 지시 없음)
 * - 인텐트 설명만 포함 (전체 시스템 프롬프트 미포함)
 * - 인텐트당 최대 3개 예시를 분류 프롬프트에 포함
 * - 컨텍스트용 최근 2턴 대화 이력 (최대 4개 메시지)
 * - 최소 출력 토큰을 위한 JSON 전용 응답 형식
 *
 * ## 오류 처리
 * 모든 LLM 오류 시 [IntentResult.unknown]을 반환 — 메인 파이프라인을 절대 차단하지 않음.
 *
 * WHY: 규칙 기반으로 분류할 수 없는 모호한 입력을 시맨틱 이해로 분류한다.
 * 토큰 비용을 최소화하기 위해 프롬프트를 극도로 간결하게 유지하고,
 * JSON 전용 응답으로 파싱을 단순화한다.
 *
 * @param chatClient 분류 LLM용 ChatClient
 * @param registry 인텐트 정의의 소스
 * @param maxExamplesPerIntent 프롬프트 내 인텐트당 최대 퓨샷 예시 수
 * @param maxConversationTurns 컨텍스트에 포함할 최대 대화 턴 수
 * @see CompositeIntentClassifier 캐스케이딩 전략에서의 활용
 * @see RuleBasedIntentClassifier 규칙 기반 대안
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
            logger.debug { "LLM 분류기: 활성화된 인텐트 없음" }
            return IntentResult.unknown(classifiedBy = CLASSIFIER_NAME)
        }

        try {
            // 분류 프롬프트를 구성하고 LLM에 전달한다
            val prompt = buildClassificationPrompt(text, enabledIntents, context)
            val response = callLlm(prompt)
            val parsed = parseResponse(response)
            val latencyMs = (System.nanoTime() - startTime) / 1_000_000
            val tokenCost = estimateTokenCost(prompt, response)

            if (parsed == null) {
                // R307 fix: LLM raw response 전체를 로깅하면 prompt injection/민감 정보 노출.
                // 첫 100자 + 전체 길이만 출력하여 디버그 정보는 유지하되 정보 노출은 차단.
                logger.warn { "LLM 분류기: 응답 파싱 실패: ${sanitizeForLog(response)}" }
                return IntentResult.unknown(
                    classifiedBy = CLASSIFIER_NAME,
                    tokenCost = tokenCost,
                    latencyMs = latencyMs
                )
            }

            if (parsed.intents.isEmpty()) {
                return IntentResult.unknown(
                    classifiedBy = CLASSIFIER_NAME,
                    tokenCost = tokenCost,
                    latencyMs = latencyMs
                )
            }

            val primary = parsed.intents.first()
            val secondary = parsed.intents.drop(1)

            logger.debug {
                "LLM 분류기: 인텐트=${primary.intentName} " +
                    "신뢰도=${primary.confidence} 토큰비용=$tokenCost 지연=${latencyMs}ms"
            }

            return IntentResult(
                primary = primary,
                secondary = secondary,
                classifiedBy = CLASSIFIER_NAME,
                tokenCost = tokenCost,
                latencyMs = latencyMs
            )
        } catch (e: Exception) {
            // CancellationException은 반드시 재전파
            e.throwIfCancellation()
            val latencyMs = (System.nanoTime() - startTime) / 1_000_000
            logger.error(e) { "LLM 분류기: 분류 실패, unknown 반환" }
            return IntentResult.unknown(
                classifiedBy = CLASSIFIER_NAME,
                latencyMs = latencyMs
            )
        }
    }

    /**
     * 분류 프롬프트를 구성한다.
     *
     * 인텐트 설명, 예시, 대화 컨텍스트를 포함하되
     * 토큰 소비를 최소화하기 위해 간결하게 유지한다.
     *
     * @param text 사용자 입력
     * @param intents 활성화된 인텐트 목록
     * @param context 분류 컨텍스트
     * @return 구성된 프롬프트 문자열
     */
    internal fun buildClassificationPrompt(
        text: String,
        intents: List<IntentDefinition>,
        context: ClassificationContext
    ): String {
        // 각 인텐트의 설명과 예시를 간결하게 포매팅한다
        val intentDescriptions = intents.joinToString("\n") { intent ->
            val examples = intent.examples.take(maxExamplesPerIntent)
            val exampleLines = if (examples.isNotEmpty()) {
                "\n  Examples:\n" + examples.joinToString("\n") { "    - \"$it\"" }
            } else ""
            "- ${intent.name}: ${intent.description}$exampleLines"
        }

        // 최근 대화 컨텍스트를 구성한다 (최대 턴 수 제한)
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

    /**
     * 대화 컨텍스트를 구성한다.
     * 최근 N턴(N * 2 메시지)만 포함하여 토큰을 절약한다.
     * 각 메시지 내용은 200자로 잘라 프롬프트 크기를 제한한다.
     */
    private fun buildConversationContext(context: ClassificationContext): String {
        val history = context.resolveConversationHistory()
        if (history.isEmpty()) return ""

        val recentMessages = history.takeLast(maxConversationTurns * 2)
        return recentMessages.joinToString("\n") { msg ->
            "- ${msg.role.name}: ${msg.content.take(200)}"
        }
    }

    /**
     * LLM을 호출하여 분류 결과를 받아온다.
     * WHY: runInterruptible(Dispatchers.IO)로 감싸는 이유는 ChatClient.call()이
     * 블로킹 호출이기 때문이다. 코루틴 컨텍스트에서 IO 디스패처로 오프로딩한다.
     */
    private suspend fun callLlm(prompt: String): String {
        val response: ChatResponse? = runInterruptible(Dispatchers.IO) {
            chatClient
                .prompt()
                .user(prompt)
                .call()
                .chatResponse()
        }

        return response?.result?.output?.text.orEmpty()
    }

    /**
     * LLM 응답을 파싱하여 분류된 인텐트 목록을 추출한다.
     * 코드 펜스(```)를 제거하고 JSON을 파싱한다.
     * "unknown"이거나 신뢰도가 0인 인텐트는 필터링한다.
     *
     * @param response LLM 응답 문자열
     * @return 파싱된 결과, 또는 파싱 실패 시 null
     */
    internal fun parseResponse(response: String): ParsedClassificationResponse? {
        return try {
            val cleaned = response
                .replace(CODE_FENCE_REGEX, "")
                .trim()

            val json = objectMapper.readValue<LlmClassificationResponse>(cleaned)
            val validIntents = json.intents
                .filter { it.name != "unknown" && it.confidence > 0.0 }
                .map { ClassifiedIntent(intentName = it.name, confidence = it.confidence.coerceIn(0.0, 1.0)) }
                .sortedByDescending { it.confidence }

            ParsedClassificationResponse(validIntents)
        } catch (e: Exception) {
            logger.debug(e) { "LLM 분류 응답 파싱 실패" }
            null
        }
    }

    /**
     * 프롬프트와 응답 길이 기반으로 토큰 비용을 추정한다.
     * WHY: 정확한 토큰 카운팅보다 빠른 추정이 분류기에서는 충분하다.
     * 문자 4개당 토큰 1개로 대략 추정한다.
     */
    private fun estimateTokenCost(prompt: String, response: String): Int {
        return (prompt.length + response.length) / 4
    }

    /** LLM 응답 JSON 구조 */
    private data class LlmClassificationResponse(
        val intents: List<LlmClassifiedIntent> = emptyList()
    )

    /** LLM이 반환하는 단일 분류 항목 */
    private data class LlmClassifiedIntent(
        val name: String,
        val confidence: Double
    )

    /** 파싱된 분류 응답 */
    internal data class ParsedClassificationResponse(
        val intents: List<ClassifiedIntent>
    )

    companion object {
        /** 분류기 식별 이름 */
        const val CLASSIFIER_NAME = "llm"

        /** 로그 출력 시 노출할 응답 최대 길이 */
        internal const val LOG_RESPONSE_MAX_CHARS = 100

        /** JSON 코드 펜스 제거용 정규식 */
        private val CODE_FENCE_REGEX = Regex("```(?:json)?\\s*|```")

        /** 시스템 지시문 — 최소 토큰을 위해 간결하게 유지 */
        private const val SYSTEM_INSTRUCTION =
            "Classify the user's intent. Return JSON only, no explanation."

        private val objectMapper = jacksonObjectMapper()

        /**
         * LLM 응답 문자열을 로그 출력용으로 안전하게 자른다.
         *
         * R307 fix: LLM raw 응답은 prompt injection/민감 정보/사용자 입력 echo를 포함할 수
         * 있으므로 전체를 application log에 남기면 안 된다. 첫 [LOG_RESPONSE_MAX_CHARS]
         * 문자 + 전체 길이만 출력하여 디버그에 필요한 정보는 남기고 노출은 차단한다.
         *
         * @param response LLM raw 응답
         * @return `"<처음 100자>... (total N자)"` 또는 짧은 응답은 그대로
         */
        internal fun sanitizeForLog(response: String): String {
            if (response.length <= LOG_RESPONSE_MAX_CHARS) {
                return "${'"'}$response${'"'} (${response.length}자)"
            }
            return "${'"'}${response.take(LOG_RESPONSE_MAX_CHARS)}${'"'}... (total ${response.length}자)"
        }
    }
}
