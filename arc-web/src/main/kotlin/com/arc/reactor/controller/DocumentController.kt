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
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.security.MessageDigest
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
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "409", description = "Document with identical content already exists"),
        ApiResponse(responseCode = "500", description = "Embedding or storage failure")
    ])
    @PostMapping
    fun addDocument(
        @Valid @RequestBody request: AddDocumentRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val contentHash = computeSha256(request.content)
        val existing = findByContentHash(contentHash)
        if (existing != null) return duplicateConflictResponse(existing.id)

        val id = UUID.randomUUID().toString()
        val metadata = request.metadata?.toMutableMap() ?: mutableMapOf()
        metadata[CONTENT_HASH_KEY] = contentHash

        val document = Document(id, request.content, metadata)
        val chunks = documentChunkerProvider.ifAvailable?.chunk(document)
            ?: listOf(document)

        try {
            vectorStore.add(chunks)
        } catch (e: Exception) {
            logger.error(e) { "Failed to embed document: id=$id" }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ErrorResponse(
                    error = "Failed to embed document: ${e.message}",
                    timestamp = Instant.now().toString()
                )
            )
        }

        logger.info {
            "Document added: id=$id, chunks=${chunks.size}, " +
                "contentLength=${request.content.length}"
        }

        val chunked = chunks.size > 1
        return ResponseEntity.status(HttpStatus.CREATED).body(
            DocumentResponse(
                id = id,
                content = request.content,
                metadata = metadata,
                chunkCount = chunks.size,
                chunkIds = if (chunked) chunks.map { it.id } else emptyList()
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
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "409", description = "Document with identical content already exists"),
        ApiResponse(responseCode = "500", description = "Embedding or storage failure")
    ])
    @PostMapping("/batch")
    fun addDocuments(
        @Valid @RequestBody request: BatchAddDocumentRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val hashes = request.documents.map { computeSha256(it.content) }
        for (hash in hashes) {
            val existing = findByContentHash(hash)
            if (existing != null) return duplicateConflictResponse(existing.id)
        }

        val chunker = documentChunkerProvider.ifAvailable
        val documents = request.documents.mapIndexed { index, doc ->
            val id = UUID.randomUUID().toString()
            val metadata = doc.metadata?.toMutableMap() ?: mutableMapOf()
            metadata[CONTENT_HASH_KEY] = hashes[index]
            Document(id, doc.content, metadata)
        }

        val chunks = chunker?.chunk(documents) ?: documents

        try {
            vectorStore.add(chunks)
        } catch (e: Exception) {
            logger.error(e) { "Failed to embed batch of ${documents.size} documents" }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ErrorResponse(
                    error = "Failed to embed documents: ${e.message}",
                    timestamp = Instant.now().toString()
                )
            )
        }

        logger.info { "Batch added ${documents.size} documents (${chunks.size} chunks)" }

        return ResponseEntity.status(HttpStatus.CREATED).body(
            BatchDocumentResponse(
                count = documents.size,
                totalChunks = chunks.size,
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
        @Valid @RequestBody request: DeleteDocumentRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        // Each ID may be a parent document — derive deterministic chunk IDs to clean up.
        // VectorStore.delete is idempotent: non-existent IDs are silently ignored.
        // Skip derivation for IDs that are already chunk IDs.
        // Brute-force: derive up to maxNumChunks IDs per parent. VectorStore.delete() is
        // idempotent so phantom IDs are silently ignored. A lookup-first approach would require
        // a getById() not present in Spring AI VectorStore, so this trade-off is intentional.
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

    // ---- Deduplication helpers ----

    private fun computeSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun findByContentHash(hash: String): Document? {
        val filter = FilterExpressionBuilder()
        val searchRequest = SearchRequest.builder()
            .query("duplicate check")
            .topK(1)
            .filterExpression(filter.eq(CONTENT_HASH_KEY, hash).build())
            .build()
        return vectorStore.similaritySearch(searchRequest).firstOrNull()
    }

    private fun duplicateConflictResponse(existingId: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to DUPLICATE_ERROR_MESSAGE, "existingId" to existingId)
        )
    }

    companion object {
        const val CONTENT_HASH_KEY = "content_hash"
        private const val DUPLICATE_ERROR_MESSAGE =
            "Document with identical content already exists"
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
        val totalChunks: Int,
        val ids: List<String>
    )

    data class SearchResultResponse(
        val id: String,
        val content: String,
        val metadata: Map<String, Any>,
        val score: Double?
    )
}
