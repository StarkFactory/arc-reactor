package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class DocumentControllerTest {

    private lateinit var vectorStore: VectorStore
    private lateinit var controller: DocumentController

    @BeforeEach
    fun setup() {
        vectorStore = mockk(relaxed = true)
        controller = DocumentController(vectorStore)
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
        fun `should return 201 for admin`() {
            val request = DocumentController.AddDocumentRequest(content = "Test content")

            val response = controller.addDocument(request, adminExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Admin should be able to add documents" }
            verify(exactly = 1) { vectorStore.add(any()) }
        }

        @Test
        fun `should return 403 for non-admin user`() {
            val request = DocumentController.AddDocumentRequest(content = "Test content")

            val response = controller.addDocument(request, userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "USER should get 403" }
            verify(exactly = 0) { vectorStore.add(any()) }
        }

        @Test
        fun `should return 403 when auth is disabled`() {
            val request = DocumentController.AddDocumentRequest(content = "Test content")

            val response = controller.addDocument(request, noAuthExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should fail-close when no auth" }
        }

        @Test
        fun `should include metadata in document`() {
            val request = DocumentController.AddDocumentRequest(
                content = "Content",
                metadata = mapOf("source" to "test")
            )

            val response = controller.addDocument(request, adminExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should create document with metadata" }
        }
    }

    @Nested
    inner class BatchAddDocuments {

        @Test
        fun `should return 201 for admin batch add`() {
            val request = DocumentController.BatchAddDocumentRequest(
                documents = listOf(
                    DocumentController.AddDocumentRequest(content = "Doc 1"),
                    DocumentController.AddDocumentRequest(content = "Doc 2")
                )
            )

            val response = controller.addDocuments(request, adminExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Admin should be able to batch add" }
        }

        @Test
        fun `should return 403 for non-admin batch add`() {
            val request = DocumentController.BatchAddDocumentRequest(
                documents = listOf(DocumentController.AddDocumentRequest(content = "Doc"))
            )

            val response = controller.addDocuments(request, userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "USER should get 403 on batch add" }
        }
    }

    @Nested
    inner class SearchDocuments {

        @Test
        fun `should allow search without admin check`() {
            every { vectorStore.similaritySearch(any<SearchRequest>()) } returns listOf(
                Document("id-1", "Found content", mapOf<String, Any>())
            )

            val request = DocumentController.SearchDocumentRequest(query = "test query")
            val results = controller.searchDocuments(request)

            assertEquals(1, results.size) { "Should return search results" }
            assertEquals("Found content", results[0].content) { "Content should match" }
        }
    }

    @Nested
    inner class DeleteDocuments {

        @Test
        fun `should return 204 for admin delete`() {
            val request = DocumentController.DeleteDocumentRequest(ids = listOf("id-1", "id-2"))

            val response = controller.deleteDocuments(request, adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "Admin should be able to delete" }
            verify(exactly = 1) { vectorStore.delete(listOf("id-1", "id-2")) }
        }

        @Test
        fun `should return 403 for non-admin delete`() {
            val request = DocumentController.DeleteDocumentRequest(ids = listOf("id-1"))

            val response = controller.deleteDocuments(request, userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "USER should get 403 on delete" }
            verify(exactly = 0) { vectorStore.delete(any<List<String>>()) }
        }
    }
}
