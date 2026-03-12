package com.arc.reactor.rag.impl

import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder

private val logger = KotlinLogging.logger {}

/**
 * Spring AI VectorStore-Based Document Retriever
 *
 * Performs vector search using Spring AI's VectorStore interface.
 * Supports PGVector, Pinecone, Milvus, Chroma, and other VectorStore implementations.
 */
class SpringAiVectorStoreRetriever(
    private val vectorStore: VectorStore,
    private val defaultSimilarityThreshold: Double = 0.7,
    private val timeoutMs: Long = 5000
) : DocumentRetriever {

    override suspend fun retrieve(
        queries: List<String>,
        topK: Int,
        filters: Map<String, Any>
    ): List<RetrievedDocument> {
        logger.debug { "Retrieving documents for ${queries.size} queries, topK=$topK, filters=$filters" }

        // Search across multiple queries and deduplicate
        val allDocuments = queries.flatMap { query ->
            searchWithQuery(query, topK, filters)
        }

        // Sort by score first, then deduplicate (keeps highest-scored version per ID)
        return allDocuments
            .sortedByDescending { it.score }
            .distinctBy { it.id }
            .take(topK)
    }

    private suspend fun searchWithQuery(
        query: String,
        topK: Int,
        filters: Map<String, Any> = emptyMap()
    ): List<RetrievedDocument> {
        return try {
            val builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(defaultSimilarityThreshold)

            // Apply metadata filters if provided
            if (filters.isNotEmpty()) {
                val filterExpression = buildFilterExpression(filters)
                if (filterExpression != null) {
                    builder.filterExpression(filterExpression)
                }
            }

            val searchRequest = builder.build()

            // similaritySearch is a blocking call; withTimeout cancels the coroutine
            // while withContext(Dispatchers.IO) offloads blocking work from the caller's thread.
            val documents = withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    vectorStore.similaritySearch(searchRequest)
                }
            }

            documents.map { doc -> doc.toRetrievedDocument() }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "Vector search timed out after ${timeoutMs}ms for query: $query" }
            emptyList()
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Vector search failed for query: $query" }
            emptyList()
        }
    }

    /**
     * Build a Spring AI filter expression from a metadata filter map.
     * Combines multiple filters with AND logic.
     */
    private fun buildFilterExpression(
        filters: Map<String, Any>
    ): org.springframework.ai.vectorstore.filter.Filter.Expression? {
        if (filters.isEmpty()) return null

        val b = FilterExpressionBuilder()
        val expressions = filters.map { (key, value) ->
            b.eq(key, value)
        }

        return if (expressions.size == 1) {
            expressions.first().build()
        } else {
            expressions.reduce { acc, expr -> b.and(acc, expr) }.build()
        }
    }

    private fun Document.toRetrievedDocument(): RetrievedDocument {
        val meta = this.metadata
        return RetrievedDocument(
            id = this.id,
            content = this.text ?: "",
            metadata = meta,
            score = run {
                val distance = meta["distance"]?.toString()?.toDoubleOrNull()
                if (distance != null) {
                    // PGVector distance is lower-is-better; convert to higher-is-better score
                    (1.0 - distance).coerceIn(0.0, 1.0)
                } else {
                    meta["score"]?.toString()?.toDoubleOrNull() ?: 0.0
                }
            },
            source = meta["source"]?.toString()
        )
    }
}

/**
 * In-Memory Document Retriever
 *
 * Simple keyword-matching search without VectorStore.
 * For testing and development use.
 */
class InMemoryDocumentRetriever(
    private val documents: MutableList<RetrievedDocument> = java.util.concurrent.CopyOnWriteArrayList()
) : DocumentRetriever {

    fun addDocument(document: RetrievedDocument) {
        documents.add(document)
    }

    fun addDocuments(docs: List<RetrievedDocument>) {
        documents.addAll(docs)
    }

    fun clear() {
        documents.clear()
    }

    override suspend fun retrieve(
        queries: List<String>,
        topK: Int,
        filters: Map<String, Any>
    ): List<RetrievedDocument> {
        // Simple keyword-matching search
        val queryTerms = queries.flatMap { it.lowercase().split(" ") }.toSet()

        return documents
            .filter { doc -> matchesFilters(doc, filters) }
            .map { doc ->
                val matchScore = calculateMatchScore(doc.content.lowercase(), queryTerms)
                doc.copy(score = matchScore)
            }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun matchesFilters(doc: RetrievedDocument, filters: Map<String, Any>): Boolean {
        if (filters.isEmpty()) return true
        return filters.all { (key, value) ->
            doc.metadata[key]?.toString() == value.toString()
        }
    }

    private fun calculateMatchScore(content: String, queryTerms: Set<String>): Double {
        val matchCount = queryTerms.count { term -> content.contains(term) }
        return matchCount.toDouble() / queryTerms.size.coerceAtLeast(1)
    }
}
