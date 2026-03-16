package com.arc.reactor.rag.impl

import com.arc.reactor.rag.ContextCompressor
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * LLM-based Contextual Compressor
 *
 * Uses an LLM to extract only query-relevant information from each document.
 * Documents with no relevant content are removed entirely.
 *
 * Based on RECOMP (Xu et al., 2024): selective augmentation via extractive compression.
 *
 * ## Behavior
 * - Short documents (< [minContentLength] chars) are passed through without LLM call
 * - Each document is compressed in parallel via coroutines
 * - If LLM responds with "IRRELEVANT", the document is removed
 * - On LLM failure, the original document is preserved (graceful degradation)
 *
 * @param chatClient Spring AI ChatClient for compression calls
 * @param minContentLength Documents shorter than this are skipped (default: 200 chars)
 */
class LlmContextualCompressor(
    private val chatClient: ChatClient,
    private val minContentLength: Int = DEFAULT_MIN_CONTENT_LENGTH
) : ContextCompressor {

    override suspend fun compress(
        query: String,
        documents: List<RetrievedDocument>
    ): List<RetrievedDocument> {
        if (documents.isEmpty()) return emptyList()

        logger.debug { "Compressing ${documents.size} documents for query: $query" }

        val compressed = coroutineScope {
            documents.map { doc ->
                async { compressDocument(query, doc) }
            }.mapNotNull { it.await() }
        }

        logger.debug {
            "Compression complete: ${documents.size} -> ${compressed.size} documents"
        }
        return compressed
    }

    private suspend fun compressDocument(
        query: String,
        document: RetrievedDocument
    ): RetrievedDocument? {
        if (document.content.length < minContentLength) {
            return document
        }

        return try {
            val extracted = callLlm(query, document.content)
            when {
                extracted == null -> document
                IRRELEVANT_PATTERN.matches(extracted.trim()) -> {
                    logger.debug { "Document ${document.id} irrelevant to query" }
                    null
                }
                extracted.isBlank() -> document
                else -> document.copy(content = extracted.trim())
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) {
                "Compression failed for document ${document.id}, keeping original"
            }
            document
        }
    }

    private suspend fun callLlm(query: String, content: String): String? {
        return withContext(Dispatchers.IO) {
            val userPrompt = EXTRACTION_PROMPT
                .replace("{query}", query)
                .replace("{content}", content)

            chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .chatResponse()
                ?.result
                ?.output
                ?.text
        }
    }

    companion object {
        internal const val DEFAULT_MIN_CONTENT_LENGTH = 200

        private val IRRELEVANT_PATTERN = Regex("(?i)irrelevant[.!]?")

        internal const val SYSTEM_PROMPT =
            "You are a document compression assistant. " +
                "Extract only the information relevant to the user's query. " +
                "Remove all irrelevant content. " +
                "If nothing is relevant, respond with exactly \"IRRELEVANT\"."

        internal const val EXTRACTION_PROMPT =
            "Query: {query}\n\nDocument:\n{content}\n\nRelevant extract:"
    }
}
