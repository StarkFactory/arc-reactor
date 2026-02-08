package com.arc.reactor.rag

import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument

/**
 * RAG (Retrieval-Augmented Generation) Pipeline
 *
 * Retrieves relevant documents from a knowledge base to augment LLM responses
 * with factual, up-to-date information.
 *
 * ## Pipeline Stages
 * ```
 * Query → [QueryTransformer] → [Retriever] → [Reranker] → [ContextBuilder] → RagContext
 * ```
 *
 * ## Stage Descriptions
 * 1. **QueryTransformer**: Rewrites/expands query for better retrieval
 * 2. **Retriever**: Fetches candidate documents from vector store
 * 3. **Reranker**: Re-scores documents for relevance
 * 4. **ContextBuilder**: Formats documents for LLM consumption
 *
 * ## Example Usage
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
 * // Use context.content in LLM prompt
 * val answer = llm.chat(systemPrompt + context.content + userQuestion)
 * ```
 *
 * @see RagQuery for query configuration
 * @see RagContext for retrieval results
 */
interface RagPipeline {
    /**
     * Execute the RAG pipeline.
     *
     * @param query Search query with configuration
     * @return Retrieved context ready for LLM consumption
     */
    suspend fun retrieve(query: RagQuery): RagContext
}

/**
 * Query Transformer Interface
 *
 * Transforms user queries into more effective search queries.
 *
 * ## Techniques
 * - **Query Rewriting**: Fix typos, clarify ambiguous terms
 * - **Query Expansion**: Add synonyms, related terms
 * - **HyDE**: Generate hypothetical document, use as query
 * - **Multi-Query**: Generate multiple query variations
 *
 * ## Example
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
     * Transform a single query into one or more search queries.
     *
     * @param query Original user query
     * @return List of transformed queries (may be 1 or multiple)
     */
    suspend fun transform(query: String): List<String>
}

/**
 * Document Retriever Interface
 *
 * Retrieves relevant documents from a vector store or search index.
 *
 * ## Implementations
 * - [com.arc.reactor.rag.impl.SpringAiVectorStoreRetriever]: Spring AI VectorStore integration
 * - Custom implementations for Elasticsearch, OpenSearch, etc.
 *
 * ## Example
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
     * Retrieve documents matching the queries.
     *
     * @param queries Search queries (typically from QueryTransformer)
     * @param topK Maximum number of documents to retrieve
     * @param filters Metadata filters to narrow results (e.g., {"source": "docs", "category": "policy"})
     * @return List of retrieved documents with similarity scores
     */
    suspend fun retrieve(
        queries: List<String>,
        topK: Int = 10,
        filters: Map<String, Any> = emptyMap()
    ): List<RetrievedDocument>
}

/**
 * Document Reranker Interface
 *
 * Re-ranks retrieved documents to improve relevance ordering.
 *
 * ## Why Reranking?
 * - Vector similarity doesn't always match semantic relevance
 * - Rerankers use more sophisticated models for scoring
 * - Can incorporate diversity to avoid redundant results
 *
 * ## Implementations
 * - [com.arc.reactor.rag.impl.SimpleScoreReranker]: Pass-through by score
 * - [com.arc.reactor.rag.impl.KeywordWeightedReranker]: Boost keyword matches
 * - [com.arc.reactor.rag.impl.DiversityReranker]: MMR-based diversity
 *
 * ## Example
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
     * Rerank documents based on relevance to the query.
     *
     * @param query Original user query for relevance comparison
     * @param documents Documents to rerank
     * @param topK Number of top documents to return
     * @return Reranked documents (most relevant first)
     */
    suspend fun rerank(
        query: String,
        documents: List<RetrievedDocument>,
        topK: Int = 5
    ): List<RetrievedDocument>
}

/**
 * Context Builder Interface
 *
 * Assembles retrieved documents into a formatted context string
 * suitable for inclusion in LLM prompts.
 *
 * ## Considerations
 * - Token limits: Stay within LLM context window
 * - Formatting: Clear structure helps LLM understand context
 * - Source attribution: Include document IDs for citations
 *
 * ## Example Output
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
     * Build context string from retrieved documents.
     *
     * @param documents Reranked documents to include
     * @param maxTokens Maximum tokens for the context (approximate)
     * @return Formatted context string for LLM prompt
     */
    fun build(documents: List<RetrievedDocument>, maxTokens: Int = 4000): String
}
