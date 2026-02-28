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
 * Hybrid RAG Pipeline — BM25 + Vector Search fused with Reciprocal Rank Fusion.
 *
 * Combines dense (vector) and sparse (BM25) retrieval to improve search quality
 * for queries containing proper nouns such as team names, system names, and person names
 * that standard vector search may under-rank.
 *
 * Pipeline stages:
 * ```
 * Query → [QueryTransformer] → {Vector Retrieval ‖ BM25 Search} → RRF Fusion → [Reranker] → Context
 * ```
 *
 * @param retriever      Dense vector retriever (e.g., SpringAiVectorStoreRetriever)
 * @param bm25Scorer     In-memory BM25 scorer (pre-indexed with the document corpus)
 * @param queryTransformer Optional query expansion/rewriting step
 * @param reranker         Optional cross-encoder or MMR reranker applied after fusion
 * @param contextBuilder   Formats selected documents for LLM consumption
 * @param vectorWeight     RRF weight for vector search ranks (default 0.5)
 * @param bm25Weight       RRF weight for BM25 ranks (default 0.5)
 * @param rrfK             RRF smoothing constant — higher values reduce rank-position sensitivity
 * @param maxContextTokens Maximum context tokens passed to [ContextBuilder]
 * @param tokenEstimator   Token count estimator for context size accounting
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

        // 1. Query Transform
        val transformedQueries = if (queryTransformer != null) {
            queryTransformer.transform(query.query)
        } else {
            listOf(query.query)
        }

        // 2. Vector retrieval
        val vectorDocs = retriever.retrieve(transformedQueries, query.topK * 2, query.filters)
        logger.debug { "Vector search returned ${vectorDocs.size} documents" }

        if (vectorDocs.isEmpty() && bm25Scorer.size == 0) {
            return RagContext.EMPTY
        }

        // 3. BM25 retrieval (search the in-memory index)
        val primaryQuery = transformedQueries.first()
        val bm25Results = bm25Scorer.search(primaryQuery, query.topK * 2)
        logger.debug { "BM25 search returned ${bm25Results.size} documents" }

        // 4. RRF fusion
        val vectorRanked = vectorDocs.map { it.id to it.score }
        val fusedRanked = fuseWithCustomK(vectorRanked, bm25Results)

        if (fusedRanked.isEmpty()) {
            return RagContext.EMPTY
        }

        // 5. Resolve fused IDs back to documents (use vector content)
        val docById = vectorDocs.associateBy { it.id }
        val fusedDocuments = fusedRanked.take(query.topK).mapNotNull { (docId, rrfScore) ->
            docById[docId]?.copy(score = rrfScore)
        }

        if (fusedDocuments.isEmpty()) {
            return RagContext.EMPTY
        }

        // 7. Optional reranking
        val finalDocs = if (query.rerank && reranker != null) {
            reranker.rerank(query.query, fusedDocuments, query.topK)
        } else {
            fusedDocuments.take(query.topK)
        }

        // 8. Build context
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
     * Index a document in the BM25 scorer so future queries can find it.
     *
     * Call this whenever a new document is added to the backing VectorStore.
     */
    fun indexDocument(doc: RetrievedDocument) {
        bm25Scorer.index(doc.id, doc.content)
    }

    /**
     * Bulk-index documents in the BM25 scorer.
     */
    fun indexDocuments(docs: List<RetrievedDocument>) {
        for (doc in docs) {
            bm25Scorer.index(doc.id, doc.content)
        }
    }

    /** Apply RRF fusion with the configured K and weights. */
    private fun fuseWithCustomK(
        vectorResults: List<Pair<String, Double>>,
        bm25Results: List<Pair<String, Double>>
    ): List<Pair<String, Double>> {
        val scores = HashMap<String, Double>()

        vectorResults.forEachIndexed { rank, (docId, _) ->
            scores[docId] = (scores[docId] ?: 0.0) + vectorWeight / (rrfK + rank + 1)
        }

        bm25Results.forEachIndexed { rank, (docId, _) ->
            scores[docId] = (scores[docId] ?: 0.0) + bm25Weight / (rrfK + rank + 1)
        }

        return scores.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }
}
