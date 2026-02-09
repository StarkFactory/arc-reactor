package com.arc.reactor.controller

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import mu.KotlinLogging
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Document Management API Controller
 *
 * Provides REST APIs for managing documents in the VectorStore (RAG knowledge base).
 * Only available when a VectorStore bean exists (requires PGVector or similar).
 *
 * ## Endpoints
 * - POST   /api/documents         : Add a document (embeds and stores)
 * - POST   /api/documents/search  : Similarity search
 * - DELETE /api/documents         : Delete documents by IDs
 */
@Tag(name = "Documents", description = "RAG document management (requires VectorStore)")
@RestController
@RequestMapping("/api/documents")
@ConditionalOnProperty(prefix = "arc.reactor.rag", name = ["enabled"], havingValue = "true")
class DocumentController(
    private val vectorStore: VectorStore
) {

    /**
     * Add a document to the vector store.
     * The document content is automatically embedded and stored.
     */
    @PostMapping
    fun addDocument(@Valid @RequestBody request: AddDocumentRequest): ResponseEntity<DocumentResponse> {
        val id = UUID.randomUUID().toString()
        val metadata = request.metadata?.toMutableMap() ?: mutableMapOf()

        val document = Document(id, request.content, metadata)
        vectorStore.add(listOf(document))

        logger.info { "Document added: id=$id, contentLength=${request.content.length}" }

        return ResponseEntity.status(HttpStatus.CREATED).body(
            DocumentResponse(
                id = id,
                content = request.content,
                metadata = metadata
            )
        )
    }

    /**
     * Add multiple documents at once.
     */
    @PostMapping("/batch")
    fun addDocuments(@Valid @RequestBody request: BatchAddDocumentRequest): ResponseEntity<BatchDocumentResponse> {
        val documents = request.documents.map { doc ->
            val id = UUID.randomUUID().toString()
            val metadata = doc.metadata?.toMutableMap() ?: mutableMapOf()
            Document(id, doc.content, metadata)
        }

        vectorStore.add(documents)

        logger.info { "Batch added ${documents.size} documents" }

        return ResponseEntity.status(HttpStatus.CREATED).body(
            BatchDocumentResponse(
                count = documents.size,
                ids = documents.map { it.id }
            )
        )
    }

    /**
     * Search documents by similarity.
     */
    @PostMapping("/search")
    fun searchDocuments(@Valid @RequestBody request: SearchDocumentRequest): List<SearchResultResponse> {
        val searchRequest = SearchRequest.builder()
            .query(request.query)
            .topK(request.topK ?: 5)
            .similarityThreshold(request.similarityThreshold ?: 0.0)
            .build()

        val results = vectorStore.similaritySearch(searchRequest)

        logger.debug { "Search '${request.query}' returned ${results.size} results" }

        return results.map { doc ->
            SearchResultResponse(
                id = doc.id,
                content = doc.text ?: "",
                metadata = doc.metadata,
                score = doc.metadata["distance"]?.toString()?.toDoubleOrNull()
            )
        }
    }

    /**
     * Delete documents by IDs.
     */
    @DeleteMapping
    fun deleteDocuments(@RequestBody request: DeleteDocumentRequest): ResponseEntity<Any> {
        vectorStore.delete(request.ids)
        logger.info { "Deleted ${request.ids.size} documents" }
        return ResponseEntity.noContent().build()
    }

    // ---- DTOs ----

    data class AddDocumentRequest(
        @field:NotBlank(message = "Document content is required")
        val content: String,
        val metadata: Map<String, Any>? = null
    )

    data class BatchAddDocumentRequest(
        val documents: List<AddDocumentRequest>
    )

    data class SearchDocumentRequest(
        @field:NotBlank(message = "Search query is required")
        val query: String,
        val topK: Int? = 5,
        val similarityThreshold: Double? = 0.0
    )

    data class DeleteDocumentRequest(
        val ids: List<String>
    )

    data class DocumentResponse(
        val id: String,
        val content: String,
        val metadata: Map<String, Any>
    )

    data class BatchDocumentResponse(
        val count: Int,
        val ids: List<String>
    )

    data class SearchResultResponse(
        val id: String,
        val content: String,
        val metadata: Map<String, Any>,
        val score: Double?
    )
}
