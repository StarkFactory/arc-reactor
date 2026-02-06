package com.arc.reactor.rag.impl

import com.arc.reactor.rag.DocumentReranker
import com.arc.reactor.rag.model.RetrievedDocument
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Simple Score-Based Reranker
 *
 * Re-ranks using raw search scores.
 * For production, consider CrossEncoder models or LLM-based rerankers.
 */
class SimpleScoreReranker : DocumentReranker {

    override suspend fun rerank(
        query: String,
        documents: List<RetrievedDocument>,
        topK: Int
    ): List<RetrievedDocument> {
        logger.debug { "Reranking ${documents.size} documents, topK=$topK" }

        // Sort by score descending
        return documents
            .sortedByDescending { it.score }
            .take(topK)
    }
}

/**
 * Keyword-Weighted Reranker
 *
 * Re-ranks using query keyword frequency as weight.
 */
class KeywordWeightedReranker(
    private val keywordWeight: Double = 0.3
) : DocumentReranker {

    override suspend fun rerank(
        query: String,
        documents: List<RetrievedDocument>,
        topK: Int
    ): List<RetrievedDocument> {
        logger.debug { "Keyword-weighted reranking ${documents.size} documents" }

        val queryTerms = query.lowercase().split(" ").filter { it.isNotBlank() }

        return documents.map { doc ->
            val keywordScore = calculateKeywordScore(doc.content, queryTerms)
            val combinedScore = doc.score * (1 - keywordWeight) + keywordScore * keywordWeight
            doc.copy(score = combinedScore)
        }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun calculateKeywordScore(content: String, queryTerms: List<String>): Double {
        if (queryTerms.isEmpty()) return 0.0

        val lowerContent = content.lowercase()
        val matchCount = queryTerms.count { term -> lowerContent.contains(term) }
        return matchCount.toDouble() / queryTerms.size
    }
}

/**
 * Diversity-Based Reranker (MMR - Maximal Marginal Relevance)
 *
 * Balances relevance and diversity when re-ranking.
 * Avoids duplicate documents and provides diverse information.
 */
class DiversityReranker(
    private val lambda: Double = 0.5  // Relevance (1.0) vs Diversity (0.0) balance
) : DocumentReranker {

    override suspend fun rerank(
        query: String,
        documents: List<RetrievedDocument>,
        topK: Int
    ): List<RetrievedDocument> {
        if (documents.isEmpty()) return emptyList()

        logger.debug { "MMR reranking ${documents.size} documents with lambda=$lambda" }

        val selected = mutableListOf<RetrievedDocument>()
        val remaining = documents.toMutableList()

        // First document: highest score
        val firstDoc = remaining.maxByOrNull { it.score } ?: return emptyList()
        selected.add(firstDoc)
        remaining.remove(firstDoc)

        // Remaining documents: select using MMR
        while (selected.size < topK && remaining.isNotEmpty()) {
            val nextDoc = remaining.maxByOrNull { candidate ->
                val relevance = candidate.score
                val maxSimilarity = selected.maxOfOrNull { selected ->
                    calculateSimilarity(candidate.content, selected.content)
                } ?: 0.0

                // MMR = λ * Rel(d, q) - (1-λ) * max(Sim(d, d_i))
                lambda * relevance - (1 - lambda) * maxSimilarity
            } ?: break

            selected.add(nextDoc)
            remaining.remove(nextDoc)
        }

        return selected
    }

    private fun calculateSimilarity(content1: String, content2: String): Double {
        // Simple Jaccard similarity
        val words1 = content1.lowercase().split(" ").toSet()
        val words2 = content2.lowercase().split(" ").toSet()

        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size

        return if (union > 0) intersection.toDouble() / union else 0.0
    }
}
