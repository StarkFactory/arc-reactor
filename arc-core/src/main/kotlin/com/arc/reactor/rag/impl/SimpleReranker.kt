package com.arc.reactor.rag.impl

import com.arc.reactor.rag.DocumentReranker
import com.arc.reactor.rag.model.RetrievedDocument
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 단순 점수 기반 리랭커.
 *
 * 원본 검색 점수를 기준으로 정렬한다.
 * 프로덕션 환경에서는 CrossEncoder 모델이나 LLM 기반 리랭커를 고려하라.
 */
class SimpleScoreReranker : DocumentReranker {

    override suspend fun rerank(
        query: String,
        documents: List<RetrievedDocument>,
        topK: Int
    ): List<RetrievedDocument> {
        logger.debug { "Reranking ${documents.size} documents, topK=$topK" }

        // 점수 내림차순 정렬 후 상위 topK만 반환
        return documents
            .sortedByDescending { it.score }
            .take(topK)
    }
}

/**
 * 키워드 가중 리랭커.
 *
 * 쿼리 키워드의 출현 빈도를 가중치로 사용하여 리랭킹한다.
 * 원본 검색 점수와 키워드 매칭 점수를 혼합한다.
 *
 * @param keywordWeight 키워드 점수 가중치 (0.0~1.0). 기본값 0.3은
 *   원본 점수의 70%와 키워드 점수의 30%를 혼합한다는 의미.
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
            // 키워드 매칭 점수: 쿼리 단어 중 문서에 포함된 비율 (0.0~1.0)
            val keywordScore = calculateKeywordScore(doc.content, queryTerms)
            // 원본 점수와 키워드 점수를 가중 혼합
            val combinedScore = doc.score * (1 - keywordWeight) + keywordScore * keywordWeight
            doc.copy(score = combinedScore)
        }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /** 쿼리 단어 중 문서 내용에 포함된 비율을 계산한다. */
    private fun calculateKeywordScore(content: String, queryTerms: List<String>): Double {
        if (queryTerms.isEmpty()) return 0.0

        val lowerContent = content.lowercase()
        val matchCount = queryTerms.count { term -> lowerContent.contains(term) }
        return matchCount.toDouble() / queryTerms.size
    }
}

/**
 * 다양성 기반 리랭커 (MMR - Maximal Marginal Relevance).
 *
 * 관련도와 다양성의 균형을 맞추어 리랭킹한다.
 * 중복 문서를 피하고 다양한 정보를 제공한다.
 *
 * ## MMR 공식
 * ```
 * MMR(d) = λ * Relevance(d) - (1 - λ) * max_s∈S Similarity(d, s)
 * ```
 * - λ: 관련도(1.0)와 다양성(0.0) 간의 균형 파라미터
 * - Relevance(d): 문서의 검색 점수
 * - max Similarity: 이미 선택된 문서들과의 최대 유사도
 *
 * ## 왜 MMR인가?
 * 상위 검색 결과가 비슷한 내용을 담고 있으면 LLM에 중복 정보가 전달된다.
 * MMR은 관련도가 높으면서도 기존 선택과 다른 문서를 우선 선택하여
 * 컨텍스트 윈도우를 다양한 정보로 채운다.
 *
 * @param lambda 관련도(1.0) vs 다양성(0.0) 균형 파라미터.
 *   0.5가 기본값으로 두 요소를 동등하게 고려한다.
 */
class DiversityReranker(
    private val lambda: Double = 0.5
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

        // 첫 문서: 순수 관련도 기준으로 최고 점수 문서를 선택
        val firstDoc = remaining.maxByOrNull { it.score } ?: return emptyList()
        selected.add(firstDoc)
        remaining.remove(firstDoc)

        // 이후 문서: MMR 공식으로 관련도와 다양성을 균형 잡아 선택
        while (selected.size < topK && remaining.isNotEmpty()) {
            val nextDoc = selectNextMmrDocument(remaining, selected) ?: break
            selected.add(nextDoc)
            remaining.remove(nextDoc)
        }

        return selected
    }

    /**
     * MMR 점수가 가장 높은 다음 문서를 선택한다.
     * MMR = λ * 관련도 - (1-λ) * (이미 선택된 문서들과의 최대 유사도)
     */
    private fun selectNextMmrDocument(
        remaining: List<RetrievedDocument>,
        selected: List<RetrievedDocument>
    ): RetrievedDocument? {
        return remaining.maxByOrNull { candidate ->
            val relevance = candidate.score
            val maxSimilarity = selected.maxOfOrNull {
                calculateSimilarity(candidate.content, it.content)
            } ?: 0.0
            // MMR 공식: 관련도를 높이면서 이미 선택된 것과의 유사도를 낮추는 문서 선호
            lambda * relevance - (1 - lambda) * maxSimilarity
        }
    }

    /**
     * 단순 Jaccard 유사도를 계산한다.
     * 두 문서의 단어 집합 교집합 / 합집합.
     *
     * 왜 Jaccard인가: 임베딩 없이 텍스트만으로 유사도를 빠르게 근사할 수 있다.
     * 프로덕션에서는 임베딩 기반 코사인 유사도가 더 정확하다.
     */
    private fun calculateSimilarity(content1: String, content2: String): Double {
        val words1 = content1.lowercase().split(" ").toSet()
        val words2 = content2.lowercase().split(" ").toSet()

        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size

        return if (union > 0) intersection.toDouble() / union else 0.0
    }
}
