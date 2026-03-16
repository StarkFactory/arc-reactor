package com.arc.reactor.rag.impl

import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.rag.ContextBuilder
import com.arc.reactor.rag.DocumentReranker
import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.QueryTransformer
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.rag.search.Bm25Scorer
import com.arc.reactor.rag.search.RrfFusion
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 하이브리드 RAG 파이프라인 — BM25 + Vector Search를 Reciprocal Rank Fusion으로 융합.
 *
 * Dense(Vector)와 Sparse(BM25) 검색을 결합하여 검색 품질을 향상시킨다.
 * 팀명, 시스템명, 인명 등의 고유명사를 포함하는 쿼리에 특히 효과적이다.
 * 표준 Vector Search는 이런 고유명사를 어휘 불일치(vocabulary mismatch)로 놓칠 수 있다.
 *
 * ## 파이프라인 단계
 * ```
 * Query → [QueryTransformer] → {Vector Retrieval ‖ BM25 Search} → RRF Fusion → [Reranker] → Context
 * ```
 *
 * ## 왜 하이브리드인가?
 * - Vector: 의미적 유사성에 강하지만 정확한 키워드 매칭이 약함
 * - BM25: 키워드 매칭에 강하지만 동의어/의미 이해가 약함
 * - 두 방법을 융합하면 서로의 약점을 보완하여 전체 성능이 향상됨
 *
 * @param retriever      Dense 벡터 검색기 (예: SpringAiVectorStoreRetriever)
 * @param bm25Scorer     인메모리 BM25 스코어러 (문서 코퍼스로 사전 인덱싱 필요)
 * @param queryTransformer 선택적 쿼리 확장/재작성 단계
 * @param reranker         융합 후 적용되는 선택적 리랭커 (CrossEncoder, MMR 등)
 * @param contextBuilder   선택된 문서를 LLM 소비용으로 포맷팅
 * @param vectorWeight     RRF에서 Vector Search 순위에 적용할 가중치 (기본값 0.5)
 * @param bm25Weight       RRF에서 BM25 순위에 적용할 가중치 (기본값 0.5)
 * @param rrfK             RRF 스무딩 상수 — 높을수록 순위 위치 민감도 감소
 * @param maxContextTokens [ContextBuilder]에 전달할 최대 컨텍스트 토큰
 * @param tokenEstimator   컨텍스트 크기 계산을 위한 토큰 추정기
 *
 * @see RagPipeline 인터페이스 계약
 */
class HybridRagPipeline(
    private val retriever: DocumentRetriever,
    private val bm25Scorer: Bm25Scorer,
    private val queryTransformer: QueryTransformer? = null,
    private val reranker: DocumentReranker? = null,
    private val contextBuilder: ContextBuilder = SimpleContextBuilder(),
    private val vectorWeight: Double = 0.5,
    private val bm25Weight: Double = 0.5,
    private val rrfK: Double = RrfFusion.K,
    private val maxContextTokens: Int = 4000,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : RagPipeline {

    override suspend fun retrieve(query: RagQuery): RagContext {
        logger.debug { "Hybrid RAG pipeline started: ${query.query}" }

        // 1단계: 쿼리 변환
        val transformedQueries = if (queryTransformer != null) {
            queryTransformer.transform(query.query)
        } else {
            listOf(query.query)
        }

        // 2단계: Vector 검색 — topK * 2로 넉넉하게 가져와서 RRF 후 필터링 여유를 둔다
        val vectorDocs = retriever.retrieve(transformedQueries, query.topK * 2, query.filters)
        logger.debug { "Vector search returned ${vectorDocs.size} documents" }

        if (vectorDocs.isEmpty() && bm25Scorer.size == 0) {
            return RagContext.EMPTY
        }

        // 3단계: BM25 검색 — 인메모리 인덱스에서 키워드 기반 검색
        val primaryQuery = transformedQueries.first()
        val bm25Results = bm25Scorer.search(primaryQuery, query.topK * 2)
        logger.debug { "BM25 search returned ${bm25Results.size} documents" }

        // 4단계: RRF 융합 — 두 검색 결과의 순위를 역순위 점수로 합산
        val vectorRanked = vectorDocs.map { it.id to it.score }
        val fusedRanked = RrfFusion.fuse(vectorRanked, bm25Results, vectorWeight, bm25Weight, rrfK)

        if (fusedRanked.isEmpty()) {
            return RagContext.EMPTY
        }

        // 5단계: 융합된 ID를 실제 문서로 복원 — BM25에서만 검색된 문서도 포함
        val vectorDocsById = vectorDocs.associateBy { it.id }
        val bm25OnlyDocs = bm25Results
            .map { (docId, _) -> docId }
            .filter { docId -> docId !in vectorDocsById }
            .mapNotNull { docId ->
                bm25Scorer.getContent(docId)?.let { content ->
                    RetrievedDocument(id = docId, content = content, score = 0.0)
                }
            }
            .associateBy { it.id }
        val allDocsById = vectorDocsById + bm25OnlyDocs
        val fusedDocuments = fusedRanked.take(query.topK).mapNotNull { (docId, rrfScore) ->
            allDocsById[docId]?.copy(score = rrfScore)
        }

        if (fusedDocuments.isEmpty()) {
            return RagContext.EMPTY
        }

        // 6단계: 선택적 리랭킹
        val finalDocs = if (query.rerank && reranker != null) {
            reranker.rerank(query.query, fusedDocuments, query.topK)
        } else {
            fusedDocuments.take(query.topK)
        }

        // 7단계: 컨텍스트 빌드
        val context = contextBuilder.build(finalDocs, maxContextTokens)

        logger.debug {
            "Hybrid RAG complete: vectorDocs=${vectorDocs.size}, " +
                "bm25Docs=${bm25Results.size}, fused=${fusedDocuments.size}"
        }

        return RagContext(
            context = context,
            documents = finalDocs,
            totalTokens = tokenEstimator.estimate(context)
        )
    }

    /**
     * BM25 스코어러에 문서를 인덱싱하여 이후 쿼리에서 검색 가능하게 한다.
     * 새 문서가 VectorStore에 추가될 때마다 호출해야 한다.
     */
    fun indexDocument(doc: RetrievedDocument) {
        bm25Scorer.index(doc.id, doc.content)
    }

    /**
     * BM25 스코어러에 여러 문서를 일괄 인덱싱한다.
     */
    fun indexDocuments(docs: List<RetrievedDocument>) {
        for (doc in docs) {
            bm25Scorer.index(doc.id, doc.content)
        }
    }

}
