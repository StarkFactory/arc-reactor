package com.arc.reactor.rag.impl

import com.arc.reactor.rag.QueryTransformer
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * Conversation-Aware Query Transformer
 *
 * Rewrites the user's query by incorporating conversation context,
 * resolving pronouns and references to produce a standalone search query.
 *
 * ## How It Works
 * ```
 * Conversation:
 *   User: "Tell me about the return policy"
 *   AI: "Items can be returned within 30 days..."
 *   User: "What about electronics?"        ← ambiguous without context
 *
 * → LLM rewrites to: "What is the return policy for electronics?"
 * → This standalone query retrieves better documents
 * ```
 *
 * ## Why It Works
 * In multi-turn conversations, users naturally use pronouns ("it", "that")
 * and implicit references. These make poor search queries. By rewriting the query
 * to be self-contained, retrieval accuracy improves significantly.
 *
 * **Thread safety:** Uses ThreadLocal to store per-request conversation history.
 * Safe for singleton bean usage across concurrent requests.
 * Call [updateHistory] before each [transform] invocation.
 *
 * @param chatClient Spring AI ChatClient for query rewriting
 * @param maxHistoryTurns Maximum conversation turns to include (default: 5)
 */
class ConversationAwareQueryTransformer(
    private val chatClient: ChatClient,
    private val maxHistoryTurns: Int = 5
) : QueryTransformer {

    private val historyHolder = ThreadLocal<List<String>>()

    /**
     * Update the conversation history before calling the RAG pipeline.
     * Call this each time before pipeline.retrieve() with the latest conversation.
     * Uses ThreadLocal for thread safety — each thread sees its own history.
     */
    fun updateHistory(history: List<String>) {
        historyHolder.set(history.takeLast(maxHistoryTurns))
    }

    override suspend fun transform(query: String): List<String> {
        val history = historyHolder.get().orEmpty()
        try {
            if (history.isEmpty()) {
                return listOf(query)
            }

            return try {
                val rewrittenQuery = rewriteQuery(query, history)
                if (rewrittenQuery.isNullOrBlank() || rewrittenQuery == query) {
                    listOf(query)
                } else {
                    listOf(rewrittenQuery)
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "Conversation-aware query rewriting failed, falling back to original query" }
                listOf(query)
            }
        } finally {
            historyHolder.remove()
        }
    }

    private fun rewriteQuery(query: String, history: List<String>): String? {
        val historyText = history.joinToString("\n")
        val userMessage = "Conversation history:\n$historyText\n\nCurrent query: $query"

        return chatClient.prompt()
            .system(SYSTEM_PROMPT)
            .user(userMessage)
            .call()
            .chatResponse()
            ?.result
            ?.output
            ?.text
            ?.trim()
    }

    companion object {
        internal const val SYSTEM_PROMPT =
            "You are a query rewriter. Given a conversation history and a current query, " +
                "rewrite the query to be a standalone search query that doesn't need conversation context. " +
                "Resolve all pronouns (it, that, they, etc.) and implicit references. " +
                "Output ONLY the rewritten query, nothing else. " +
                "If the query is already self-contained, return it unchanged."
    }
}
