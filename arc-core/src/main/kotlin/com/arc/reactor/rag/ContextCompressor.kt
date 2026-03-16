package com.arc.reactor.rag

import com.arc.reactor.rag.model.RetrievedDocument

/**
 * Contextual Compression Interface
 *
 * Compresses retrieved documents by extracting only query-relevant information,
 * removing irrelevant content to optimize LLM context window usage.
 *
 * Inspired by RECOMP (Xu et al., 2024): "Improving Retrieval-Augmented LMs
 * with Compression and Selective Augmentation" (arXiv:2310.04408).
 *
 * ## Pipeline Position
 * ```
 * Query → Retrieve → Rerank → **Compress** → ContextBuilder → LLM
 * ```
 *
 * ## Example
 * ```kotlin
 * val compressor = LlmContextualCompressor(chatClient)
 * val compressed = compressor.compress("return policy", rerankedDocs)
 * // Documents now contain only return-policy-relevant content
 * ```
 *
 * @see com.arc.reactor.rag.impl.LlmContextualCompressor
 */
interface ContextCompressor {

    /**
     * Compress documents by extracting only query-relevant content.
     *
     * Documents deemed entirely irrelevant are removed from the result.
     * Metadata is preserved on compressed documents.
     *
     * @param query The user query for relevance assessment
     * @param documents Documents to compress
     * @return Compressed documents with only relevant content (may be fewer than input)
     */
    suspend fun compress(query: String, documents: List<RetrievedDocument>): List<RetrievedDocument>
}
