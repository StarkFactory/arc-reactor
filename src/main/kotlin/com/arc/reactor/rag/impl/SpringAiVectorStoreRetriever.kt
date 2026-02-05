package com.arc.reactor.rag.impl

import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.model.RetrievedDocument
import mu.KotlinLogging
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore

private val logger = KotlinLogging.logger {}

/**
 * Spring AI VectorStore 기반 문서 검색기
 *
 * Spring AI의 VectorStore 인터페이스를 사용하여 벡터 검색 수행.
 * PGVector, Pinecone, Milvus, Chroma 등 다양한 VectorStore 지원.
 */
class SpringAiVectorStoreRetriever(
    private val vectorStore: VectorStore,
    private val defaultSimilarityThreshold: Double = 0.7
) : DocumentRetriever {

    override suspend fun retrieve(queries: List<String>, topK: Int): List<RetrievedDocument> {
        logger.debug { "Retrieving documents for ${queries.size} queries, topK=$topK" }

        // 여러 쿼리에 대해 검색 후 중복 제거
        val allDocuments = queries.flatMap { query ->
            searchWithQuery(query, topK)
        }

        // 중복 제거 (ID 기반) 및 점수 순 정렬
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
 * 인메모리 문서 검색기
 *
 * VectorStore 없이 간단한 키워드 매칭으로 검색.
 * 테스트 및 개발용.
 */
class InMemoryDocumentRetriever(
    private val documents: MutableList<RetrievedDocument> = mutableListOf()
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
        // 간단한 키워드 매칭 기반 검색
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
