package com.arc.reactor.rag.impl

import com.arc.reactor.rag.QueryComplexity
import com.arc.reactor.rag.QueryRouter
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * LLM 기반 적응형 쿼리 라우터.
 *
 * ChatModel을 사용하여 쿼리 복잡도를 세 레벨 중 하나로 분류한다:
 * [QueryComplexity.NO_RETRIEVAL], [QueryComplexity.SIMPLE], [QueryComplexity.COMPLEX].
 *
 * LLM 호출 실패 또는 타임아웃 시 [QueryComplexity.SIMPLE]로 폴백한다 (우아한 성능 저하).
 * 왜 SIMPLE로 폴백하는가: 검색을 아예 건너뛰는 것(NO_RETRIEVAL)보다는
 * 안전하게 기본 검색을 수행하는 것이 더 나은 사용자 경험을 제공한다.
 *
 * @param chatClient 분류에 사용하는 Spring AI ChatClient
 * @param timeoutMs 분류 호출의 최대 허용 시간 (기본값 3000ms)
 * @see <a href="https://arxiv.org/abs/2403.14403">Adaptive-RAG (Jeong et al., 2024)</a>
 */
class AdaptiveQueryRouter(
    private val chatClient: ChatClient,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : QueryRouter {

    override suspend fun route(query: String): QueryComplexity {
        return try {
            withTimeout(timeoutMs) {
                classify(query)
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) {
                "Adaptive routing failed, defaulting to SIMPLE"
            }
            QueryComplexity.SIMPLE
        }
    }

    /**
     * LLM을 호출하여 쿼리 복잡도를 분류한다.
     * Dispatchers.IO로 블로킹 HTTP 호출을 오프로드한다.
     */
    private suspend fun classify(query: String): QueryComplexity {
        val response = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(CLASSIFICATION_PROMPT)
                .user(query)
                .call()
                .chatResponse()
                ?.result
                ?.output
                ?.text
                .orEmpty()
                .trim()
                .uppercase()
        }

        return parseComplexity(response)
    }

    companion object {
        internal const val DEFAULT_TIMEOUT_MS = 3000L

        /** LLM에 전달하는 분류 시스템 프롬프트 */
        internal const val CLASSIFICATION_PROMPT =
            "Classify this query's complexity for retrieval: " +
                "NO_RETRIEVAL (greetings, chitchat), " +
                "SIMPLE (single fact lookup), " +
                "COMPLEX (multi-hop, comparison, analysis). " +
                "Query: {query}. " +
                "Respond with only the classification."

        /**
         * LLM 응답을 파싱하여 QueryComplexity로 변환한다.
         * 인식할 수 없는 응답이면 SIMPLE로 안전하게 폴백한다.
         */
        internal fun parseComplexity(response: String): QueryComplexity {
            val cleaned = response.trim().uppercase()
            return when {
                cleaned.contains("NO_RETRIEVAL") -> QueryComplexity.NO_RETRIEVAL
                cleaned.contains("COMPLEX") -> QueryComplexity.COMPLEX
                cleaned.contains("SIMPLE") -> QueryComplexity.SIMPLE
                else -> {
                    logger.warn {
                        "Unrecognized complexity '$response', defaulting to SIMPLE"
                    }
                    QueryComplexity.SIMPLE
                }
            }
        }
    }
}
