package com.arc.reactor.rag.impl

import com.arc.reactor.rag.DocumentReranker
import com.arc.reactor.rag.model.RetrievedDocument
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 단순 점수 기반 Reranker
 *
 * 검색 점수를 그대로 사용하여 재정렬.
 * 실제 운영에서는 CrossEncoder 모델이나 LLM 기반 Reranker 사용 권장.
 */
class SimpleScoreReranker : DocumentReranker {

    override suspend fun rerank(
        query: String,
        documents: List<RetrievedDocument>,
        topK: Int
    ): List<RetrievedDocument> {
        logger.debug { "Reranking ${documents.size} documents, topK=$topK" }

        // 단순히 점수 기준 정렬
        return documents
            .sortedByDescending { it.score }
            .take(topK)
    }
}

/**
 * 키워드 가중치 기반 Reranker
 *
 * 쿼리 키워드 출현 빈도를 가중치로 사용하여 재정렬.
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
 * 다양성 기반 Reranker (MMR - Maximal Marginal Relevance)
 *
 * 관련성과 다양성을 균형있게 고려하여 재정렬.
 * 중복 문서를 피하고 다양한 정보를 제공.
 */
class DiversityReranker(
    private val lambda: Double = 0.5  // 관련성(1.0) vs 다양성(0.0) 균형
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

        // 첫 번째 문서: 가장 높은 점수
        val firstDoc = remaining.maxByOrNull { it.score } ?: return emptyList()
        selected.add(firstDoc)
        remaining.remove(firstDoc)

        // 나머지 문서: MMR 방식으로 선택
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
        // 간단한 Jaccard 유사도
        val words1 = content1.lowercase().split(" ").toSet()
        val words2 = content2.lowercase().split(" ").toSet()

        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size

        return if (union > 0) intersection.toDouble() / union else 0.0
    }
}
