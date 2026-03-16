package com.arc.reactor.rag.impl

import com.arc.reactor.rag.QueryTransformer
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * Decomposition Query Transformer
 *
 * Breaks down complex multi-hop questions into simpler sub-queries that can be
 * independently searched, then combines results for comprehensive retrieval.
 *
 * ## How It Works
 * ```
 * User query: "How does our return policy compare to competitors
 *              and what impact does it have on customer retention?"
 *
 * → LLM decomposes into:
 *   1. "What is our return policy?"
 *   2. "What are competitor return policies?"
 *   3. "How does return policy affect customer retention?"
 *
 * → Each sub-query is searched independently
 * → Results are merged for comprehensive context
 * ```
 *
 * ## Why It Works
 * Complex questions often span multiple topics. A single vector search may miss
 * relevant documents that address only part of the question. By decomposing into
 * focused sub-queries, each retrieval targets a specific aspect, improving recall.
 *
 * @param chatClient Spring AI ChatClient for query decomposition
 * @see <a href="https://arxiv.org/abs/2205.10625">Least-to-Most Prompting (Zhou et al., 2023)</a>
 */
class DecompositionQueryTransformer(
    private val chatClient: ChatClient
) : QueryTransformer {

    override suspend fun transform(query: String): List<String> {
        return try {
            val subQueries = decomposeQuery(query)
            if (subQueries.isEmpty()) {
                logger.warn { "Decomposition returned empty result, falling back to original query" }
                listOf(query)
            } else {
                (subQueries + query).distinct()
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Query decomposition failed, falling back to original query" }
            listOf(query)
        }
    }

    private suspend fun decomposeQuery(query: String): List<String> {
        return withContext(Dispatchers.IO) {
            val response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(query)
                .call()
                .chatResponse()
                ?.result?.output?.text

            parseSubQueries(response)
        }
    }

    companion object {
        internal const val SYSTEM_PROMPT =
            "Break down this complex question into 2-4 simpler sub-questions that can be " +
                "independently searched. If the question is already simple, return it as-is.\n\n" +
                "Respond with one sub-question per line, no numbering or bullets."

        internal fun parseSubQueries(response: String?): List<String> {
            if (response.isNullOrBlank()) return emptyList()
            return response.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
    }
}
