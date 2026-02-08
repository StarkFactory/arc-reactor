package com.arc.reactor.rag.impl

import com.arc.reactor.rag.QueryTransformer
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
 * **Thread safety:** This class holds mutable conversation history state.
 * Do NOT share a single instance across concurrent requests.
 * Create a new instance per request, or call [updateHistory] exclusively per-request.
 *
 * @param chatClient Spring AI ChatClient for query rewriting
 * @param conversationHistory Recent conversation turns (set before each pipeline call)
 * @param maxHistoryTurns Maximum conversation turns to include (default: 5)
 */
class ConversationAwareQueryTransformer(
    private val chatClient: ChatClient,
    private var conversationHistory: List<String> = emptyList(),
    private val maxHistoryTurns: Int = 5
) : QueryTransformer {

    /**
     * Update the conversation history before calling the RAG pipeline.
     * Call this each time before pipeline.retrieve() with the latest conversation.
     */
    fun updateHistory(history: List<String>) {
        this.conversationHistory = history.takeLast(maxHistoryTurns)
    }

    override suspend fun transform(query: String): List<String> {
        if (conversationHistory.isEmpty()) {
            return listOf(query)
        }

        return try {
            val rewrittenQuery = rewriteQuery(query)
            if (rewrittenQuery.isNullOrBlank() || rewrittenQuery == query) {
                listOf(query)
            } else {
                listOf(rewrittenQuery)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Conversation-aware query rewriting failed, falling back to original query" }
            listOf(query)
        }
    }

    private fun rewriteQuery(query: String): String? {
        val historyText = conversationHistory.joinToString("\n")
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
