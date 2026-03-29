package com.arc.reactor.rag.impl

import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder

private val logger = KotlinLogging.logger {}

/**
 * Spring AI VectorStore 기반 문서 검색기.
 *
 * Spring AI의 VectorStore 인터페이스를 사용하여 벡터 검색을 수행한다.
 * PGVector, Pinecone, Milvus, Chroma 등 다양한 VectorStore 구현체를 지원한다.
 *
 * @param vectorStore Spring AI VectorStore 인스턴스
 * @param defaultSimilarityThreshold 최소 유사도 임계값 (기본값 0.7).
 *   왜 0.7인가: 너무 낮으면 노이즈가 많아지고, 너무 높으면 관련 문서를 놓친다.
 *   0.7은 정밀도와 재현율의 적절한 균형점.
 * @param timeoutMs 검색 타임아웃 (기본값 5000ms)
 */
class SpringAiVectorStoreRetriever(
    private val vectorStore: VectorStore,
    private val defaultSimilarityThreshold: Double = 0.7,
    private val timeoutMs: Long = 5000
) : DocumentRetriever {

    override suspend fun retrieve(
        queries: List<String>,
        topK: Int,
        filters: Map<String, Any>
    ): List<RetrievedDocument> {
        logger.debug { "${queries.size}개 쿼리로 문서 검색 시작, topK=$topK, filters=$filters" }

        // 여러 쿼리에 대해 검색을 수행하고 결과를 합친다
        val allDocuments = queries.flatMap { query ->
            searchWithQuery(query, topK, filters)
        }

        // 점수 내림차순 정렬 후 ID 중복 제거 (가장 높은 점수의 버전만 유지)
        return allDocuments
            .sortedByDescending { it.score }
            .distinctBy { it.id }
            .take(topK)
    }

    /**
     * 단일 쿼리로 VectorStore를 검색한다.
     * 타임아웃과 에러 핸들링을 포함한다.
     */
    private suspend fun searchWithQuery(
        query: String,
        topK: Int,
        filters: Map<String, Any> = emptyMap()
    ): List<RetrievedDocument> {
        return try {
            val builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(defaultSimilarityThreshold)

            // 메타데이터 필터가 있으면 적용
            if (filters.isNotEmpty()) {
                val filterExpression = buildFilterExpression(filters)
                if (filterExpression != null) {
                    builder.filterExpression(filterExpression)
                }
            }

            val searchRequest = builder.build()

            // similaritySearch는 블로킹 호출이다.
            // withTimeout으로 코루틴 타임아웃을 적용하고,
            // withContext(Dispatchers.IO)로 호출자 스레드에서 블로킹 작업을 오프로드한다.
            val documents = withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    vectorStore.similaritySearch(searchRequest)
                }
            }

            documents.map { doc -> doc.toRetrievedDocument() }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "벡터 검색 타임아웃 (${timeoutMs}ms): $query" }
            emptyList()
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "벡터 검색 실패: $query" }
            emptyList()
        }
    }

    /**
     * 메타데이터 필터 맵으로부터 Spring AI 필터 표현식을 빌드한다.
     * 여러 필터를 AND 로직으로 결합한다.
     */
    private fun buildFilterExpression(
        filters: Map<String, Any>
    ): org.springframework.ai.vectorstore.filter.Filter.Expression? {
        if (filters.isEmpty()) return null

        val b = FilterExpressionBuilder()
        val expressions = filters.map { (key, value) ->
            b.eq(key, value)
        }

        return if (expressions.size == 1) {
            expressions.first().build()
        } else {
            expressions.reduce { acc, expr -> b.and(acc, expr) }.build()
        }
    }

    /**
     * Spring AI Document를 Arc Reactor의 RetrievedDocument로 변환한다.
     *
     * PGVector의 distance는 "낮을수록 좋음" 방식이므로
     * (1.0 - distance)로 "높을수록 좋음" 점수로 변환한다.
     */
    private fun Document.toRetrievedDocument(): RetrievedDocument {
        val meta = this.metadata
        return RetrievedDocument(
            id = this.id,
            content = this.text ?: "",
            metadata = meta,
            score = run {
                val distance = meta["distance"]?.toString()?.toDoubleOrNull()
                if (distance != null) {
                    // PGVector distance는 낮을수록 좋으므로 → 높을수록 좋은 점수로 변환
                    (1.0 - distance).coerceIn(0.0, 1.0)
                } else {
                    meta["score"]?.toString()?.toDoubleOrNull() ?: 0.0
                }
            },
            source = meta["source"]?.toString()
        )
    }
}

/**
 * 인메모리 문서 검색기.
 *
 * VectorStore 없이 단순 키워드 매칭으로 검색한다.
 * 테스트 및 개발 용도.
 */
class InMemoryDocumentRetriever(
    private val documents: MutableList<RetrievedDocument> = java.util.concurrent.CopyOnWriteArrayList()
) : DocumentRetriever {

    /** 문서를 추가한다. */
    fun addDocument(document: RetrievedDocument) {
        documents.add(document)
    }

    /** 여러 문서를 일괄 추가한다. */
    fun addDocuments(docs: List<RetrievedDocument>) {
        documents.addAll(docs)
    }

    /** 모든 문서를 제거한다. */
    fun clear() {
        documents.clear()
    }

    override suspend fun retrieve(
        queries: List<String>,
        topK: Int,
        filters: Map<String, Any>
    ): List<RetrievedDocument> {
        // 모든 쿼리의 단어를 합쳐서 키워드 집합으로 사용
        val queryTerms = queries.flatMap { it.lowercase().split(" ") }.toSet()

        return documents
            .filter { doc -> matchesFilters(doc, filters) }
            .map { doc ->
                val matchScore = calculateMatchScore(doc.content.lowercase(), queryTerms)
                doc.copy(score = matchScore)
            }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /** 메타데이터 필터와 일치하는지 확인한다. */
    private fun matchesFilters(doc: RetrievedDocument, filters: Map<String, Any>): Boolean {
        if (filters.isEmpty()) return true
        return filters.all { (key, value) ->
            doc.metadata[key]?.toString() == value.toString()
        }
    }

    /** 쿼리 단어 중 문서에 포함된 비율을 점수로 계산한다. */
    private fun calculateMatchScore(content: String, queryTerms: Set<String>): Double {
        val matchCount = queryTerms.count { term -> content.contains(term) }
        return matchCount.toDouble() / queryTerms.size.coerceAtLeast(1)
    }
}
