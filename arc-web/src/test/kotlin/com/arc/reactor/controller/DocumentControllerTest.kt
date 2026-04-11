package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.rag.chunking.DocumentChunker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.security.MessageDigest

/**
 * DocumentController에 대한 테스트.
 *
 * 문서 업로드/관리 REST API의 동작을 검증합니다.
 */
class DocumentControllerTest {

    private lateinit var vectorStore: VectorStore
    private lateinit var documentChunkerProvider: ObjectProvider<DocumentChunker>
    private lateinit var controller: DocumentController

    @BeforeEach
    fun setup() {
        vectorStore = mockk(relaxed = true)
        documentChunkerProvider = mockk()
        every { documentChunkerProvider.ifAvailable } returns null
        controller = DocumentController(vectorStore, documentChunkerProvider, AgentProperties())
    }

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN
        )
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER
        )
        return exchange
    }

    private fun noAuthExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>()
        return exchange
    }

    @Nested
    inner class AddDocument {

        @Test
        fun `admin에 대해 return 201해야 한다`() {
            val request = DocumentController.AddDocumentRequest(content = "Test content")

            val response = runBlocking { controller.addDocument(request, adminExchange()) }

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Admin should be able to add documents" }
            verify(exactly = 1) { vectorStore.add(any()) }
        }

        @Test
        fun `non-admin user에 대해 return 403해야 한다`() {
            val request = DocumentController.AddDocumentRequest(content = "Test content")

            val response = runBlocking { controller.addDocument(request, userExchange()) }

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "USER should get 403" }
            verify(exactly = 0) { vectorStore.add(any()) }
        }

        @Test
        fun `role is missing일 때 reject write해야 한다`() {
            val request = DocumentController.AddDocumentRequest(content = "Test content")

            val response = runBlocking { controller.addDocument(request, noAuthExchange()) }

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Missing role should be rejected"
            }
            verify(exactly = 0) { vectorStore.add(any()) }
        }

        @Test
        fun `include metadata in document해야 한다`() {
            val request = DocumentController.AddDocumentRequest(
                content = "Content",
                metadata = mapOf("source" to "test")
            )

            val response = runBlocking { controller.addDocument(request, adminExchange()) }

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should create document with metadata" }
        }

        @Test
        fun `store content_hash in metadata해야 한다`() {
            val request = DocumentController.AddDocumentRequest(content = "Hash me")

            val response = runBlocking { controller.addDocument(request, adminExchange()) }

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should create document" }
            val body = response.body as DocumentController.DocumentResponse
            assertTrue(body.metadata.containsKey("content_hash")) {
                "Metadata should contain content_hash"
            }
            assertEquals(sha256("Hash me"), body.metadata["content_hash"]) {
                "content_hash should be SHA-256 of content"
            }
        }

        @Test
        fun `duplicate content exists일 때 return 409해야 한다`() {
            val content = "Duplicate content"
            val hash = sha256(content)
            every { vectorStore.similaritySearch(any<SearchRequest>()) } returns listOf(
                Document("existing-id", content, mapOf("content_hash" to hash))
            )

            val request = DocumentController.AddDocumentRequest(content = content)
            val response = runBlocking { controller.addDocument(request, adminExchange()) }

            assertEquals(HttpStatus.CONFLICT, response.statusCode) {
                "Duplicate content should return 409"
            }
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any>
            assertEquals("existing-id", body["existingId"]) {
                "Response should include existingId"
            }
            verify(exactly = 0) { vectorStore.add(any()) }
        }
    }

    @Nested
    inner class BatchAddDocuments {

        @Test
        fun `admin batch add에 대해 return 201해야 한다`() {
            val request = DocumentController.BatchAddDocumentRequest(
                documents = listOf(
                    DocumentController.AddDocumentRequest(content = "Doc 1"),
                    DocumentController.AddDocumentRequest(content = "Doc 2")
                )
            )

            val response = runBlocking { controller.addDocuments(request, adminExchange()) }

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Admin should be able to batch add" }
        }

        @Test
        fun `non-admin batch add에 대해 return 403해야 한다`() {
            val request = DocumentController.BatchAddDocumentRequest(
                documents = listOf(DocumentController.AddDocumentRequest(content = "Doc"))
            )

            val response = runBlocking { controller.addDocuments(request, userExchange()) }

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "USER should get 403 on batch add" }
        }

        @Test
        fun `batch contains duplicate content일 때 return 409해야 한다`() {
            val content = "Already stored"
            val hash = sha256(content)
            every { vectorStore.similaritySearch(any<SearchRequest>()) } returns listOf(
                Document("existing-id", content, mapOf("content_hash" to hash))
            )

            val request = DocumentController.BatchAddDocumentRequest(
                documents = listOf(
                    DocumentController.AddDocumentRequest(content = content),
                    DocumentController.AddDocumentRequest(content = "Unique")
                )
            )

            val response = runBlocking { controller.addDocuments(request, adminExchange()) }

            assertEquals(HttpStatus.CONFLICT, response.statusCode) {
                "Batch with duplicate content should return 409"
            }
            verify(exactly = 0) { vectorStore.add(any()) }
        }
    }

    @Nested
    inner class SearchDocuments {

        @Test
        fun `R298 admin이면 search 결과를 반환한다`() {
            // R298 fix 후 admin 가드 추가 — admin은 정상 동작
            every { vectorStore.similaritySearch(any<SearchRequest>()) } returns listOf(
                Document("id-1", "Found content", mapOf<String, Any>())
            )

            val request = DocumentController.SearchDocumentRequest(query = "test query")
            val response = runBlocking { controller.searchDocuments(request, adminExchange()) }

            assertEquals(HttpStatus.OK, response.statusCode) { "Admin search should return 200" }
            @Suppress("UNCHECKED_CAST")
            val results = response.body as List<DocumentController.SearchResultResponse>
            assertEquals(1, results.size) { "Should return search results" }
            assertEquals("Found content", results[0].content) { "Content should match" }
        }

        @Test
        fun `R298 비관리자 search는 403을 반환한다`() {
            // R298 fix 검증: 이전 구현은 admin 검사 없이 누구나 search 호출 가능했고
            // topK 상한도 없어 임의 사용자가 topK=10000으로 전체 vector store 추출 가능했다.
            // R298: admin 가드 추가 → 비관리자는 403.
            val request = DocumentController.SearchDocumentRequest(query = "test query")
            val response = runBlocking { controller.searchDocuments(request, userExchange()) }

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "R298 fix: 비관리자 search는 403이어야 한다 (RAG 인덱스 콘텐츠 보호)"
            }
            verify(exactly = 0) { vectorStore.similaritySearch(any<SearchRequest>()) }
        }

        @Test
        fun `R298 미인증 search는 403을 반환한다`() {
            val request = DocumentController.SearchDocumentRequest(query = "test query")
            val response = runBlocking { controller.searchDocuments(request, noAuthExchange()) }

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "R298 fix: 미인증 search는 403이어야 한다"
            }
            verify(exactly = 0) { vectorStore.similaritySearch(any<SearchRequest>()) }
        }
    }

    @Nested
    inner class DeleteDocuments {

        @Test
        fun `admin delete에 대해 return 204해야 한다`() {
            val request = DocumentController.DeleteDocumentRequest(ids = listOf("id-1", "id-2"))

            val response = runBlocking { controller.deleteDocuments(request, adminExchange()) }

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "Admin should be able to delete" }
            verify(exactly = 1) { vectorStore.delete(any<List<String>>()) }
        }

        @Test
        fun `non-admin delete에 대해 return 403해야 한다`() {
            val request = DocumentController.DeleteDocumentRequest(ids = listOf("id-1"))

            val response = runBlocking { controller.deleteDocuments(request, userExchange()) }

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "USER should get 403 on delete" }
            verify(exactly = 0) { vectorStore.delete(any<List<String>>()) }
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
