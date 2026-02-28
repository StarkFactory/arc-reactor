package com.arc.reactor.rag.model

/**
 * Search result carrying per-signal scores from a hybrid retrieval run.
 *
 * Records the contribution of each retrieval signal so that callers can
 * inspect or log the relative impact of vector vs BM25 scoring.
 */
data class HybridSearchResult(
    /** Document identifier */
    val docId: String,
    /** Raw document text */
    val content: String,
    /** Cosine-similarity score from vector search (0.0 if not retrieved by vector) */
    val vectorScore: Double,
    /** BM25 relevance score (0.0 if not retrieved by BM25) */
    val bm25Score: Double,
    /** Final fused score from Reciprocal Rank Fusion */
    val rrfScore: Double
)
