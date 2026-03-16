package com.arc.reactor.rag

/**
 * Routes queries to appropriate retrieval strategies based on complexity classification.
 *
 * Inspired by Adaptive-RAG (Jeong et al., 2024) — instead of applying the same retrieval
 * strategy to every query, the router classifies query complexity and selects an appropriate
 * retrieval depth (or skips retrieval entirely for trivial queries).
 *
 * @see <a href="https://arxiv.org/abs/2403.14403">Adaptive-RAG Paper</a>
 */
interface QueryRouter {
    /**
     * Classify the query and return the appropriate complexity level.
     *
     * @param query User query text
     * @return Complexity classification that determines retrieval strategy
     */
    suspend fun route(query: String): QueryComplexity
}

/**
 * Query complexity levels that determine retrieval strategy.
 */
enum class QueryComplexity(val topKMultiplier: Double) {
    /** Greetings, chitchat, general conversation — skip RAG entirely. */
    NO_RETRIEVAL(0.0),

    /** Single fact lookup — use default topK. */
    SIMPLE(1.0),

    /** Multi-hop reasoning, comparison, analysis — increase topK by 3x (capped at 15). */
    COMPLEX(3.0)
}
