package com.arc.reactor.rag.impl

import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.rag.ContextBuilder
import com.arc.reactor.rag.DocumentGrader
import com.arc.reactor.rag.DocumentReranker
import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.GradingAction
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
 * Query → Transform → Retrieve → [Grade] → Rerank → Build Context
 *
 * When a [DocumentGrader] is provided, retrieved documents are evaluated
 * for relevance before reranking. If overall relevance is too low, the
 * query is rewritten and retrieval is retried once.
 */
class DefaultRagPipeline(
    private val queryTransformer: QueryTransformer? = null,
    private val retriever: DocumentRetriever,
    private val reranker: DocumentReranker? = null,
    private val grader: DocumentGrader? = null,
    private val contextBuilder: ContextBuilder = SimpleContextBuilder(),
    private val maxContextTokens: Int = 4000,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : RagPipeline {

    override suspend fun retrieve(query: RagQuery): RagContext {
        logger.debug { "RAG pipeline started: ${query.query}" }

        // 1. Query Transform
        val transformedQueries = transformQueries(query.query)

        // 2. Retrieve
        var documents = retriever.retrieve(transformedQueries, query.topK, query.filters)
        logger.debug { "Retrieved ${documents.size} documents" }

        if (documents.isEmpty()) {
            return RagContext.EMPTY
        }

        // 3. Grade (CRAG) — filter irrelevant docs, rewrite if needed
        documents = gradeAndRetryIfNeeded(query, documents)

        if (documents.isEmpty()) {
            return RagContext.EMPTY
        }

        // 4. Rerank
        val rerankedDocs = if (query.rerank && reranker != null) {
            reranker.rerank(query.query, documents, query.topK)
        } else {
            documents.take(query.topK)
        }

        // 5. Build Context (with token limit)
        val context = contextBuilder.build(rerankedDocs, maxContextTokens)

        return RagContext(
            context = context,
            documents = rerankedDocs,
            totalTokens = tokenEstimator.estimate(context)
        )
    }

    private suspend fun transformQueries(query: String): List<String> {
        return if (queryTransformer != null) {
            queryTransformer.transform(query)
        } else {
            listOf(query)
        }
    }

    private suspend fun gradeAndRetryIfNeeded(
        query: RagQuery,
        documents: List<RetrievedDocument>
    ): List<RetrievedDocument> {
        if (grader == null) return documents

        val result = grader.grade(query.query, documents)
        if (result.action != GradingAction.NEEDS_REWRITE) {
            return result.relevantDocuments
        }

        logger.info { "CRAG: relevance too low, rewriting query and retrying" }
        val rewrittenQueries = transformQueries(query.query + " (rephrased)")
        val retried = retriever.retrieve(rewrittenQueries, query.topK, query.filters)
        if (retried.isEmpty()) {
            return result.relevantDocuments
        }

        val retryResult = grader.grade(query.query, retried)
        return retryResult.relevantDocuments
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
        var docIndex = 1

        for (doc in documents) {
            val docTokens = doc.estimatedTokens
            if (currentTokens + docTokens > maxTokens) {
                break
            }

            if (sb.isNotEmpty()) {
                sb.append(separator)
            }

            sb.append("[$docIndex]")
            doc.source?.let { sb.append(" Source: $it") }
            sb.append("\n")
            sb.append(doc.content)

            currentTokens += docTokens
            docIndex++
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
