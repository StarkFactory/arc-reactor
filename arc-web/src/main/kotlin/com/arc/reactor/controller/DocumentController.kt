package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.rag.chunking.DocumentChunker
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * 문서 관리 API 컨트롤러.
 *
 * VectorStore(RAG 지식 베이스)의 문서를 관리하는 REST API를 제공합니다.
 * VectorStore 빈이 존재할 때(PGVector 등 필요)만 사용 가능합니다.
 *
 * ## 엔드포인트
 * - POST   /api/documents         : 문서 추가 (임베딩 및 저장)
 * - POST   /api/documents/batch   : 문서 일괄 추가
 * - POST   /api/documents/search  : 유사도 검색
 * - DELETE /api/documents         : ID로 문서 삭제
 *
 * @see VectorStore
 * @see DocumentChunker
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
     * 벡터 스토어에 문서를 추가한다.
     * 문서 내용이 자동으로 임베딩되어 저장된다.
     * WHY: SHA-256 해시로 중복 문서를 감지하여 불필요한 임베딩 비용을 방지한다.
     */
    @Operation(summary = "벡터 스토어에 문서 추가 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Document added to vector store"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "409", description = "Document with identical content already exists"),
        ApiResponse(responseCode = "500", description = "Embedding or storage failure")
    ])
    /**
     * R294 fix: blocking VectorStore I/O를 IO 디스패처로 격리하기 위해 suspend로 전환.
     * 이전 구현은 PGVector JDBC 호출을 Reactor Netty NIO 이벤트 루프에서 직접 실행하여
     * 임베딩/JDBC latency 동안 워커 스레드를 차단했다.
     */
    @PostMapping
    suspend fun addDocument(
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
            // R294: blocking JDBC를 IO dispatcher로 격리
            withContext(Dispatchers.IO) {
                vectorStore.add(chunks)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to embed document: id=$id" }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ErrorResponse(
                    error = "Failed to embed document",
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

    /** 여러 문서를 한 번에 추가한다. 각 문서에 대해 중복 검사를 수행한다. */
    @Operation(summary = "문서 일괄 추가 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Documents added to vector store"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "409", description = "Document with identical content already exists"),
        ApiResponse(responseCode = "500", description = "Embedding or storage failure")
    ])
    /** R294 fix: suspend로 전환 + blocking JDBC IO 격리. */
    @PostMapping("/batch")
    suspend fun addDocuments(
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
            // R294: blocking JDBC를 IO dispatcher로 격리
            withContext(Dispatchers.IO) {
                vectorStore.add(chunks)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to embed batch of ${documents.size} documents" }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ErrorResponse(
                    error = "Failed to embed documents",
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
     * 유사도 기반으로 문서를 검색한다.
     *
     * R298 fix: (1) admin 가드 추가, (2) topK upper bound는 [SearchDocumentRequest]에서
     * `@Max(100)`으로 강제. 이전 구현은 인증 없이 누구나 호출 가능 + topK 상한 없음으로,
     * 임의 사용자가 `topK=10000` 등을 보내 전체 vector store 콘텐츠를 한 번에 추출 가능
     * (정보 노출 + JDBC 부하). admin endpoint로 전환하여 RAG 인덱스 콘텐츠 보호.
     */
    @Operation(summary = "유사도 기반 문서 검색 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Similarity search results"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    /** R294 fix: suspend로 전환 + blocking JDBC IO 격리. R298 fix: admin 가드 + topK 상한. */
    @PostMapping("/search")
    suspend fun searchDocuments(
        @Valid @RequestBody request: SearchDocumentRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val searchRequest = SearchRequest.builder()
            .query(request.query)
            .topK(request.topK ?: 5)
            .similarityThreshold(request.similarityThreshold ?: 0.0)
            .build()

        // R294: blocking JDBC + 임베딩 호출을 IO dispatcher로 격리
        val results = withContext(Dispatchers.IO) {
            vectorStore.similaritySearch(searchRequest)
        }.orEmpty()

        logger.debug { "Search '${request.query}' returned ${results.size} results" }

        val responseList = results.map { doc ->
            SearchResultResponse(
                id = doc.id,
                content = doc.text ?: "",
                metadata = doc.metadata,
                score = doc.metadata["distance"]?.toString()?.toDoubleOrNull()
            )
        }
        return ResponseEntity.ok(responseList)
    }

    /**
     * ID로 문서를 삭제한다.
     * WHY: Spring AI VectorStore에는 getById()가 없으므로 청크 ID를 유도하여 삭제한다.
     * delete()는 멱등성을 보장하므로 존재하지 않는 ID는 무시된다.
     */
    @Operation(summary = "ID로 문서 삭제 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Documents deleted"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    /** R294 fix: suspend로 전환 + blocking JDBC IO 격리. */
    @DeleteMapping
    suspend fun deleteDocuments(
        @Valid @RequestBody request: DeleteDocumentRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        // 각 ID는 부모 문서일 수 있다 -- 정리를 위해 결정론적 청크 ID를 유도한다.
        // VectorStore.delete는 멱등성: 존재하지 않는 ID는 무시된다.
        // 이미 청크 ID인 경우 유도를 건너뛴다.
        // WHY: Spring AI VectorStore에 getById()가 없으므로 조회 후 삭제 방식 대신
        // 최대 maxNumChunks개까지 유도하는 brute-force 방식을 의도적으로 선택했다.
        val maxChunks = properties.rag.chunking.maxNumChunks
        val allIds = request.ids.flatMap { id ->
            if (DocumentChunker.isChunkId(id)) {
                listOf(id)
            } else {
                listOf(id) + DocumentChunker.deriveChunkIds(id, maxChunks)
            }
        }.distinct()

        // R294: blocking JDBC를 IO dispatcher로 격리
        withContext(Dispatchers.IO) {
            vectorStore.delete(allIds)
        }
        logger.info { "Deleted documents: ${request.ids.size} requested IDs -> ${allIds.size} total IDs" }
        return ResponseEntity.noContent().build()
    }

    // ---- 중복 검사 헬퍼 ----

    private fun computeSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** R294 fix: suspend로 전환 + blocking similaritySearch IO 격리. */
    private suspend fun findByContentHash(hash: String): Document? {
        val filter = FilterExpressionBuilder()
        val searchRequest = SearchRequest.builder()
            .query("duplicate check")
            .topK(1)
            .filterExpression(filter.eq(CONTENT_HASH_KEY, hash).build())
            .build()
        // R294: blocking JDBC를 IO dispatcher로 격리
        return withContext(Dispatchers.IO) {
            vectorStore.similaritySearch(searchRequest).firstOrNull()
        }
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

    // ---- 요청/응답 DTO ----

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

    /**
     * R298 fix: topK는 `@Min(1) @Max(100)`으로 제한 — 이전에는 상한이 없어 임의 사용자가
     * `topK=10000` 등을 보내 전체 vector store 콘텐츠를 한 번에 추출 가능했다.
     */
    data class SearchDocumentRequest(
        @field:NotBlank(message = "Search query is required")
        @field:Size(max = 10000, message = "Search query must not exceed 10000 characters")
        val query: String,
        @field:Min(value = 1, message = "topK must be at least 1")
        @field:Max(value = 100, message = "topK must not exceed 100")
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
