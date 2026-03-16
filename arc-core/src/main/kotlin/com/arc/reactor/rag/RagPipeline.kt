package com.arc.reactor.rag

import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument

/**
 * RAG (Retrieval-Augmented Generation) 파이프라인.
 *
 * 지식 기반에서 관련 문서를 검색하여 LLM 응답에 사실적이고 최신의 정보를 보강한다.
 *
 * ## 파이프라인 단계
 * ```
 * Query → [QueryTransformer] → [Retriever] → [Reranker] → [ContextBuilder] → RagContext
 * ```
 *
 * ## 각 단계 설명
 * 1. **QueryTransformer**: 더 나은 검색을 위해 쿼리를 재작성/확장한다
 * 2. **Retriever**: Vector Store에서 후보 문서를 가져온다
 * 3. **Reranker**: 관련도 기준으로 문서를 재점수화한다
 * 4. **ContextBuilder**: LLM 소비에 적합하게 문서를 포맷팅한다
 *
 * ## 사용 예시
 * ```kotlin
 * val pipeline = DefaultRagPipeline(
 *     transformer = HyDEQueryTransformer(llmClient),
 *     retriever = SpringAiVectorStoreRetriever(vectorStore),
 *     reranker = DiversityReranker(lambda = 0.5),
 *     contextBuilder = MarkdownContextBuilder()
 * )
 *
 * val context = pipeline.retrieve(RagQuery(
 *     query = "What is our return policy?",
 *     topK = 5
 * ))
 *
 * // LLM 프롬프트에 context.content를 포함하여 사용
 * val answer = llm.chat(systemPrompt + context.content + userQuestion)
 * ```
 *
 * @see RagQuery 쿼리 설정
 * @see RagContext 검색 결과
 * @see com.arc.reactor.rag.impl.DefaultRagPipeline 기본 구현체
 * @see com.arc.reactor.rag.impl.HybridRagPipeline BM25 + Vector 하이브리드 구현체
 */
interface RagPipeline {
    /**
     * RAG 파이프라인을 실행한다.
     *
     * @param query 설정이 포함된 검색 쿼리
     * @return LLM 소비용으로 준비된 검색 컨텍스트
     */
    suspend fun retrieve(query: RagQuery): RagContext
}

/**
 * 쿼리 변환기 인터페이스.
 *
 * 사용자 쿼리를 더 효과적인 검색 쿼리로 변환한다.
 *
 * ## 변환 기법
 * - **쿼리 재작성(Query Rewriting)**: 오타 수정, 모호한 용어 명확화
 * - **쿼리 확장(Query Expansion)**: 동의어, 관련 용어 추가
 * - **HyDE**: 가상 문서를 생성하여 쿼리로 사용
 * - **멀티쿼리(Multi-Query)**: 여러 쿼리 변형 생성
 *
 * ## 사용 예시
 * ```kotlin
 * class SynonymExpander : QueryTransformer {
 *     override suspend fun transform(query: String): List<String> {
 *         return listOf(
 *             query,
 *             addSynonyms(query),
 *             simplifyQuery(query)
 *         )
 *     }
 * }
 * ```
 */
interface QueryTransformer {
    /**
     * 단일 쿼리를 하나 이상의 검색 쿼리로 변환한다.
     *
     * @param query 원본 사용자 쿼리
     * @return 변환된 쿼리 목록 (1개 또는 여러 개)
     */
    suspend fun transform(query: String): List<String>
}

/**
 * 문서 검색기 인터페이스.
 *
 * Vector Store나 검색 인덱스에서 관련 문서를 검색한다.
 *
 * ## 구현체
 * - [com.arc.reactor.rag.impl.SpringAiVectorStoreRetriever]: Spring AI VectorStore 통합
 * - 커스텀 구현: Elasticsearch, OpenSearch 등
 *
 * ## 사용 예시
 * ```kotlin
 * val retriever = SpringAiVectorStoreRetriever(vectorStore)
 * val docs = retriever.retrieve(
 *     queries = listOf("return policy", "refund process"),
 *     topK = 10
 * )
 * ```
 */
interface DocumentRetriever {
    /**
     * 쿼리에 매칭되는 문서를 검색한다.
     *
     * @param queries 검색 쿼리 목록 (보통 QueryTransformer에서 생성)
     * @param topK 검색할 최대 문서 수
     * @param filters 메타데이터 필터 (예: {"source": "docs", "category": "policy"})
     * @return 유사도 점수가 포함된 검색 문서 목록
     */
    suspend fun retrieve(
        queries: List<String>,
        topK: Int = 10,
        filters: Map<String, Any> = emptyMap()
    ): List<RetrievedDocument>
}

/**
 * 문서 리랭커 인터페이스.
 *
 * 검색된 문서의 관련도 순서를 재조정한다.
 *
 * ## 왜 리랭킹이 필요한가?
 * - Vector 유사도가 항상 의미적 관련도와 일치하지는 않는다
 * - 리랭커는 더 정교한 모델로 스코어링을 수행한다
 * - 다양성(diversity)을 적용하여 중복 결과를 줄일 수 있다
 *
 * ## 구현체
 * - [com.arc.reactor.rag.impl.SimpleScoreReranker]: 점수 기반 정렬
 * - [com.arc.reactor.rag.impl.KeywordWeightedReranker]: 키워드 매칭 가중
 * - [com.arc.reactor.rag.impl.DiversityReranker]: MMR 기반 다양성 보장
 *
 * ## 사용 예시
 * ```kotlin
 * val reranker = DiversityReranker(lambda = 0.7)
 * val diverseDocs = reranker.rerank(
 *     query = "return policy",
 *     documents = retrievedDocs,
 *     topK = 5
 * )
 * ```
 */
interface DocumentReranker {
    /**
     * 쿼리와의 관련도 기준으로 문서를 리랭킹한다.
     *
     * @param query 관련도 비교를 위한 원본 사용자 쿼리
     * @param documents 리랭킹할 문서 목록
     * @param topK 반환할 상위 문서 수
     * @return 리랭킹된 문서 (가장 관련도 높은 것부터)
     */
    suspend fun rerank(
        query: String,
        documents: List<RetrievedDocument>,
        topK: Int = 5
    ): List<RetrievedDocument>
}

/**
 * 컨텍스트 빌더 인터페이스.
 *
 * 검색된 문서를 LLM 프롬프트에 포함하기 적합한 포맷 문자열로 조립한다.
 *
 * ## 고려 사항
 * - 토큰 제한: LLM 컨텍스트 윈도우 내에 유지
 * - 포맷팅: 명확한 구조가 LLM의 컨텍스트 이해를 돕는다
 * - 출처 표기: 인용을 위한 문서 ID 포함
 *
 * ## 출력 예시
 * ```
 * [Context Documents]
 * [1] Return Policy (2024-01-15)
 * Items can be returned within 30 days of purchase...
 *
 * [2] Refund Process (2024-02-01)
 * To request a refund, contact customer service...
 * ```
 */
interface ContextBuilder {
    /**
     * 검색된 문서로부터 컨텍스트 문자열을 빌드한다.
     *
     * @param documents 리랭킹된 문서 목록
     * @param maxTokens 컨텍스트의 최대 토큰 수 (대략적)
     * @return LLM 프롬프트에 삽입할 포맷팅된 컨텍스트 문자열
     */
    fun build(documents: List<RetrievedDocument>, maxTokens: Int = 4000): String
}
