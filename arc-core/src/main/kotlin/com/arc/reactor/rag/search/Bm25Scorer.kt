package com.arc.reactor.rag.search

import mu.KotlinLogging
import kotlin.math.ln

private val logger = KotlinLogging.logger {}

/**
 * In-memory BM25 scorer.
 *
 * Implements the BM25F ranking function for keyword-based relevance scoring.
 * Particularly effective for proper nouns (team names, system names, person names)
 * that vector search may miss due to vocabulary mismatch.
 *
 * @param k1 Term frequency saturation parameter (default 1.5)
 * @param b  Length normalization parameter (default 0.75)
 */
class Bm25Scorer(
    private val k1: Double = 1.5,
    private val b: Double = 0.75
) {

    /** docId -> token -> frequency */
    private val termFrequencies: MutableMap<String, Map<String, Int>> = LinkedHashMap()

    /** token -> number of documents containing the token */
    private val documentFrequency: MutableMap<String, Int> = HashMap()

    /** token -> precomputed IDF value (invalidated on index change) */
    private var idfCache: Map<String, Double> = emptyMap()

    /** Sum of all document lengths (in tokens) */
    private var totalLength: Long = 0L

    /** Average document length */
    private val averageLength: Double get() =
        if (termFrequencies.isEmpty()) 1.0 else totalLength.toDouble() / termFrequencies.size

    /**
     * Add or replace a document in the index.
     *
     * @param docId   Unique document identifier
     * @param content Raw document text
     */
    fun index(docId: String, content: String) {
        val tokens = tokenize(content)
        val tf = tokens.groupingBy { it }.eachCount()

        // Remove old document stats if re-indexing
        val existing = termFrequencies[docId]
        if (existing != null) {
            totalLength -= existing.values.sum()
            for (token in existing.keys) {
                val df = documentFrequency[token] ?: 1
                if (df <= 1) documentFrequency.remove(token) else documentFrequency[token] = df - 1
            }
        }

        termFrequencies[docId] = tf
        totalLength += tokens.size
        for (token in tf.keys) {
            documentFrequency[token] = (documentFrequency[token] ?: 0) + 1
        }
        idfCache = emptyMap()  // Invalidate cache
        logger.debug { "BM25: indexed doc=$docId tokens=${tokens.size}" }
    }

    /**
     * Compute BM25 score of a document against a query.
     *
     * @param query Raw query text
     * @param docId Document to score
     * @return BM25 relevance score (0.0 if document not found)
     */
    fun score(query: String, docId: String): Double {
        val tf = termFrequencies[docId] ?: return 0.0
        val docLength = tf.values.sum().toDouble()
        val avgLen = averageLength
        val idf = getIdf()

        return tokenize(query)
            .toSet()
            .sumOf { token ->
                val termFreq = tf[token]?.toDouble() ?: 0.0
                val idfScore = idf[token] ?: 0.0
                val numerator = termFreq * (k1 + 1)
                val denominator = termFreq + k1 * (1 - b + b * docLength / avgLen)
                idfScore * (numerator / denominator)
            }
    }

    /**
     * Search the index and return the top-K documents sorted by BM25 score descending.
     *
     * @param query Query text
     * @param topK  Maximum number of results
     * @return List of (docId, score) pairs, highest score first
     */
    fun search(query: String, topK: Int): List<Pair<String, Double>> {
        if (termFrequencies.isEmpty()) return emptyList()

        return termFrequencies.keys
            .map { docId -> docId to score(query, docId) }
            .filter { (_, s) -> s > 0.0 }
            .sortedByDescending { (_, s) -> s }
            .take(topK)
    }

    /** Remove all indexed documents. */
    fun clear() {
        termFrequencies.clear()
        documentFrequency.clear()
        idfCache = emptyMap()
        totalLength = 0L
    }

    /** Number of indexed documents. */
    val size: Int get() = termFrequencies.size

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun getIdf(): Map<String, Double> {
        if (idfCache.isNotEmpty()) return idfCache
        val n = termFrequencies.size.toDouble()
        idfCache = documentFrequency.mapValues { (_, df) ->
            ln((n - df + 0.5) / (df + 0.5) + 1)
        }
        return idfCache
    }

    /**
     * Tokenize text into searchable terms.
     *
     * For ASCII/Latin text: lowercase + split on non-alphanumeric characters.
     * For Korean text: each whitespace-separated word is kept as a full token AND
     * all contiguous Korean substrings of length >= MIN_TOKEN_LENGTH are emitted so
     * that a query like "플랫폼팀" matches the indexed token "플랫폼팀은".
     */
    private fun tokenize(text: String): List<String> {
        val normalized = text.lowercase()
        val words = normalized.split(TOKEN_SPLIT_REGEX).filter { it.length >= MIN_TOKEN_LENGTH }
        val extra = mutableListOf<String>()
        for (word in words) {
            // For words that contain Korean characters, generate all substrings of
            // the Korean portion so partial prefix queries can still match.
            val koreanRuns = KOREAN_RUN_REGEX.findAll(word)
            for (run in koreanRuns) {
                val s = run.value
                for (start in s.indices) {
                    for (end in (start + MIN_TOKEN_LENGTH)..s.length) {
                        val sub = s.substring(start, end)
                        if (sub != word) extra.add(sub)
                    }
                }
            }
        }
        return words + extra
    }

    companion object {
        private const val MIN_TOKEN_LENGTH = 2
        private val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9가-힣]+")
        private val KOREAN_RUN_REGEX = Regex("[가-힣]{$MIN_TOKEN_LENGTH,}")
    }
}
