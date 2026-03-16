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
 * LLM-based adaptive query router.
 *
 * Uses a ChatModel to classify query complexity into one of three levels:
 * [QueryComplexity.NO_RETRIEVAL], [QueryComplexity.SIMPLE], or [QueryComplexity.COMPLEX].
 *
 * Falls back to [QueryComplexity.SIMPLE] on LLM failure or timeout (graceful degradation).
 *
 * @param chatClient Spring AI ChatClient for classification
 * @param timeoutMs Maximum time for classification call (default 3000ms)
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

        internal const val CLASSIFICATION_PROMPT =
            "Classify this query's complexity for retrieval: " +
                "NO_RETRIEVAL (greetings, chitchat), " +
                "SIMPLE (single fact lookup), " +
                "COMPLEX (multi-hop, comparison, analysis). " +
                "Query: {query}. " +
                "Respond with only the classification."

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
