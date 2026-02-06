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
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Default RAG Pipeline Implementation
 *
 * Query → Transform → Retrieve → Rerank → Build Context
 */
class DefaultRagPipeline(
    private val queryTransformer: QueryTransformer? = null,
    private val retriever: DocumentRetriever,
    private val reranker: DocumentReranker? = null,
    private val contextBuilder: ContextBuilder = SimpleContextBuilder(),
    private val maxContextTokens: Int = 4000,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : RagPipeline {

    override suspend fun retrieve(query: RagQuery): RagContext {
        logger.debug { "RAG pipeline started: ${query.query}" }

        // 1. Query Transform
        val transformedQueries = if (queryTransformer != null) {
            queryTransformer.transform(query.query)
        } else {
            listOf(query.query)
        }

        // 2. Retrieve
        val documents = retriever.retrieve(transformedQueries, query.topK)
        logger.debug { "Retrieved ${documents.size} documents" }

        if (documents.isEmpty()) {
            return RagContext.EMPTY
        }

        // 3. Rerank
        val rerankedDocs = if (query.rerank && reranker != null) {
            reranker.rerank(query.query, documents, query.topK / 2)
        } else {
            documents.take(query.topK / 2)
        }

        // 4. Build Context (with token limit)
        val context = contextBuilder.build(rerankedDocs, maxContextTokens)

        return RagContext(
            context = context,
            documents = rerankedDocs,
            totalTokens = tokenEstimator.estimate(context)
        )
    }
}

/**
 * Simple Context Builder
 */
class SimpleContextBuilder(
    private val separator: String = "\n\n---\n\n"
) : ContextBuilder {

    override fun build(documents: List<RetrievedDocument>, maxTokens: Int): String {
        val sb = StringBuilder()
        var currentTokens = 0

        for (doc in documents) {
            val docTokens = doc.estimatedTokens
            if (currentTokens + docTokens > maxTokens) {
                break
            }

            if (sb.isNotEmpty()) {
                sb.append(separator)
            }

            // Append source information
            doc.source?.let {
                sb.append("[Source: $it]\n")
            }
            sb.append(doc.content)

            currentTokens += docTokens
        }

        return sb.toString()
    }
}

/**
 * Passthrough Query Transformer (no transformation)
 */
class PassthroughQueryTransformer : QueryTransformer {
    override suspend fun transform(query: String): List<String> = listOf(query)
}
