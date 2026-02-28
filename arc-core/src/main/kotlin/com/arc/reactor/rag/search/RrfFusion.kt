package com.arc.reactor.rag.search

/**
 * Reciprocal Rank Fusion (RRF) utility.
 *
 * Combines ranked lists from different retrieval methods (e.g., vector search and BM25)
 * into a single ranked list using the standard RRF formula:
 *
 * ```
 * RRF(doc) = sum_i( weight_i / (K + rank_i(doc)) )
 * ```
 *
 * where K=60 is the standard smoothing constant that prevents excessive boosting
 * of top-ranked documents and reduces sensitivity to rank position.
 *
 * @see <a href="https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf">RRF Paper (Cormack et al., 2009)</a>
 */
object RrfFusion {

    /** Standard RRF smoothing constant. */
    const val K = 60.0

    /**
     * Fuse two ranked lists using Reciprocal Rank Fusion.
     *
     * @param vectorResults  (docId, score) pairs ordered by score descending — from vector search
     * @param bm25Results    (docId, score) pairs ordered by score descending — from BM25
     * @param vectorWeight   Weight applied to vector search ranks (default 0.5)
     * @param bm25Weight     Weight applied to BM25 ranks (default 0.5)
     * @return (docId, rrfScore) pairs ordered by fused score descending
     */
    fun fuse(
        vectorResults: List<Pair<String, Double>>,
        bm25Results: List<Pair<String, Double>>,
        vectorWeight: Double = 0.5,
        bm25Weight: Double = 0.5
    ): List<Pair<String, Double>> {
        val scores = HashMap<String, Double>()

        vectorResults.forEachIndexed { rank, (docId, _) ->
            scores[docId] = (scores[docId] ?: 0.0) + vectorWeight / (K + rank + 1)
        }

        bm25Results.forEachIndexed { rank, (docId, _) ->
            scores[docId] = (scores[docId] ?: 0.0) + bm25Weight / (K + rank + 1)
        }

        return scores.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }
}
