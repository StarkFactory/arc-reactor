package com.arc.reactor.rag

import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument

/**
 * RAG (Retrieval-Augmented Generation) 파이프라인
 *
 * ## 파이프라인 단계
 *
 * ```
 * Query → [QueryTransformer] → [Retriever] → [Reranker] → [ContextBuilder] → RagContext
 * ```
 */
interface RagPipeline {
    /**
     * RAG 실행
     *
     * @param query 검색 쿼리
     * @return 검색 컨텍스트
     */
    suspend fun retrieve(query: RagQuery): RagContext
}

/**
 * Query 변환기
 *
 * 사용자 쿼리를 검색에 적합한 형태로 변환.
 * (Query Rewriting, Query Expansion 등)
 */
interface QueryTransformer {
    suspend fun transform(query: String): List<String>
}

/**
 * 문서 검색기
 *
 * Vector Store에서 관련 문서 검색.
 */
interface DocumentRetriever {
    suspend fun retrieve(queries: List<String>, topK: Int = 10): List<RetrievedDocument>
}

/**
 * 문서 재순위기
 *
 * 검색된 문서의 관련성을 재평가하여 순위 조정.
 */
interface DocumentReranker {
    suspend fun rerank(
        query: String,
        documents: List<RetrievedDocument>,
        topK: Int = 5
    ): List<RetrievedDocument>
}

/**
 * 컨텍스트 빌더
 *
 * 검색된 문서들을 LLM에 전달할 컨텍스트로 조합.
 */
interface ContextBuilder {
    fun build(documents: List<RetrievedDocument>, maxTokens: Int = 4000): String
}
