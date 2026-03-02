package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.rag.chunking.DocumentChunker
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import mu.KotlinLogging
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
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
    private val vectorStore: VectorStore,
    private val documentChunkerProvider: ObjectProvider<DocumentChunker>,
    private val properties: AgentProperties
) {

    /**
     * Add a document to the vector store.
     * The document content is automatically embedded and stored.
     */
    @Operation(summary = "Add a document to the vector store (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Document added to vector store"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping
    fun addDocument(
        @Valid @RequestBody request: AddDocumentRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val id = UUID.randomUUID().toString()
        val metadata = request.metadata?.toMutableMap() ?: mutableMapOf()

        val document = Document(id, request.content, metadata)
        val chunks = documentChunkerProvider.ifAvailable?.chunk(document)
            ?: listOf(document)
        vectorStore.add(chunks)

        logger.info {
            "Document added: id=$id, chunks=${chunks.size}, " +
                "contentLength=${request.content.length}"
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(
            DocumentResponse(
                id = id,
                content = request.content,
                metadata = metadata,
                chunkCount = chunks.size,
                chunkIds = chunks.map { it.id }
            )
        )
    }

    /**
     * Add multiple documents at once.
     */
    @Operation(summary = "Add multiple documents in batch (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Documents added to vector store"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping("/batch")
    fun addDocuments(
        @Valid @RequestBody request: BatchAddDocumentRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val chunker = documentChunkerProvider.ifAvailable
        val documents = request.documents.map { doc ->
            val id = UUID.randomUUID().toString()
            val metadata = doc.metadata?.toMutableMap() ?: mutableMapOf()
            Document(id, doc.content, metadata)
        }

        val chunks = chunker?.chunk(documents) ?: documents
        vectorStore.add(chunks)

        logger.info { "Batch added ${documents.size} documents (${chunks.size} chunks)" }

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
    @Operation(summary = "Search documents by similarity")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Similarity search results"),
        ApiResponse(responseCode = "400", description = "Invalid request")
    ])
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
    @Operation(summary = "Delete documents by IDs (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Documents deleted"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @DeleteMapping
    fun deleteDocuments(
        @RequestBody request: DeleteDocumentRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        // Each ID may be a parent document — derive deterministic chunk IDs to clean up.
        // VectorStore.delete is idempotent: non-existent IDs are silently ignored.
        // Skip derivation for IDs that are already chunk IDs.
        val maxChunks = properties.rag.chunking.maxNumChunks
        val allIds = request.ids.flatMap { id ->
            if (DocumentChunker.isChunkId(id)) {
                listOf(id)
            } else {
                listOf(id) + DocumentChunker.deriveChunkIds(id, maxChunks)
            }
        }.distinct()

        vectorStore.delete(allIds)
        logger.info { "Deleted documents: ${request.ids.size} requested IDs -> ${allIds.size} total IDs" }
        return ResponseEntity.noContent().build()
    }

    // ---- DTOs ----

    data class AddDocumentRequest(
        @field:NotBlank(message = "Document content is required")
        @field:Size(max = 100000, message = "Document content must not exceed 100000 characters")
        val content: String,
        @field:Size(max = 50, message = "Metadata must not exceed 50 entries")
        val metadata: Map<String, Any>? = null
    )

    data class BatchAddDocumentRequest(
        @field:NotEmpty(message = "Documents list must not be empty")
        @field:Size(max = 100, message = "Batch must not exceed 100 documents")
        val documents: List<@Valid AddDocumentRequest>
    )

    data class SearchDocumentRequest(
        @field:NotBlank(message = "Search query is required")
        @field:Size(max = 10000, message = "Search query must not exceed 10000 characters")
        val query: String,
        val topK: Int? = 5,
        val similarityThreshold: Double? = 0.0
    )

    data class DeleteDocumentRequest(
        @field:NotEmpty(message = "IDs list must not be empty")
        @field:Size(max = 100, message = "Cannot delete more than 100 documents at once")
        val ids: List<String>
    )

    data class DocumentResponse(
        val id: String,
        val content: String,
        val metadata: Map<String, Any>,
        val chunkCount: Int = 1,
        val chunkIds: List<String> = emptyList()
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
