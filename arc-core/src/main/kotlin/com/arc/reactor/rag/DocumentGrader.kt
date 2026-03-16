package com.arc.reactor.rag

import com.arc.reactor.rag.model.RetrievedDocument

/**
 * Document Grader Interface (Corrective RAG)
 *
 * Evaluates retrieved documents for relevance to the query using LLM-based grading.
 * Filters out irrelevant documents and signals when query rewriting is needed.
 *
 * ## Pipeline Integration
 * ```
 * Query -> Retrieve -> [Grade] -> Rerank -> Build Context
 * ```
 *
 * When the grader determines that overall relevance is too low (below the relevance
 * threshold), it returns [GradingAction.NEEDS_REWRITE] to signal the pipeline
 * to rewrite the query and retry retrieval.
 *
 * @see <a href="https://arxiv.org/abs/2401.15884">CRAG Paper (Yan et al., 2024)</a>
 */
interface DocumentGrader {
    /**
     * Grade retrieved documents for relevance to the query.
     *
     * @param query Original user query
     * @param documents Retrieved documents to evaluate
     * @return Grading result with filtered documents and recommended action
     */
    suspend fun grade(query: String, documents: List<RetrievedDocument>): GradingResult
}

/**
 * Result of document grading.
 *
 * @property relevantDocuments Documents that passed the relevance check
 * @property action Recommended pipeline action based on grading outcome
 */
data class GradingResult(
    val relevantDocuments: List<RetrievedDocument>,
    val action: GradingAction
)

/**
 * Recommended action after document grading.
 */
enum class GradingAction {
    /** All or most documents are relevant — use as-is */
    USE_AS_IS,

    /** Some documents were filtered out — proceed with remaining */
    FILTERED,

    /** Overall relevance is too low — query rewrite and re-retrieval recommended */
    NEEDS_REWRITE
}
