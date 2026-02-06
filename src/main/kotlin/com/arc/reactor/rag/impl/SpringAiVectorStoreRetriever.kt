package com.arc.reactor.rag.impl

import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.model.RetrievedDocument
import mu.KotlinLogging
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore

private val logger = KotlinLogging.logger {}

/**
 * Spring AI VectorStore-Based Document Retriever
 *
 * Performs vector search using Spring AI's VectorStore interface.
 * Supports PGVector, Pinecone, Milvus, Chroma, and other VectorStore implementations.
 */
class SpringAiVectorStoreRetriever(
    private val vectorStore: VectorStore,
    private val defaultSimilarityThreshold: Double = 0.7
) : DocumentRetriever {

    override suspend fun retrieve(queries: List<String>, topK: Int): List<RetrievedDocument> {
        logger.debug { "Retrieving documents for ${queries.size} queries, topK=$topK" }

        // Search across multiple queries and deduplicate
        val allDocuments = queries.flatMap { query ->
            searchWithQuery(query, topK)
        }

        // Deduplicate (by ID) and sort by score
        return allDocuments
            .distinctBy { it.id }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun searchWithQuery(query: String, topK: Int): List<RetrievedDocument> {
        return try {
            val searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(defaultSimilarityThreshold)
                .build()

            val documents = vectorStore.similaritySearch(searchRequest)

            documents.map { doc -> doc.toRetrievedDocument() }
        } catch (e: Exception) {
            logger.error(e) { "Vector search failed for query: $query" }
            emptyList()
        }
    }

    private fun Document.toRetrievedDocument(): RetrievedDocument {
        val meta = this.metadata
        return RetrievedDocument(
            id = this.id ?: java.util.UUID.randomUUID().toString(),
            content = this.text ?: "",
            metadata = meta,
            score = meta["distance"]?.toString()?.toDoubleOrNull()
                ?: meta["score"]?.toString()?.toDoubleOrNull()
                ?: 0.0,
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

    override suspend fun retrieve(queries: List<String>, topK: Int): List<RetrievedDocument> {
        // Simple keyword-matching search
        val queryTerms = queries.flatMap { it.lowercase().split(" ") }.toSet()

        return documents
            .map { doc ->
                val matchScore = calculateMatchScore(doc.content.lowercase(), queryTerms)
                doc.copy(score = matchScore)
            }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun calculateMatchScore(content: String, queryTerms: Set<String>): Double {
        val matchCount = queryTerms.count { term -> content.contains(term) }
        return matchCount.toDouble() / queryTerms.size.coerceAtLeast(1)
    }
}
