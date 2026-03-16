package com.arc.reactor.rag.impl

import com.arc.reactor.rag.model.RetrievedDocument
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

class LlmContextualCompressorTest {

    private lateinit var chatClient: ChatClient
    private lateinit var requestSpec: ChatClient.ChatClientRequestSpec
    private lateinit var callResponseSpec: ChatClient.CallResponseSpec

    @BeforeEach
    fun setup() {
        chatClient = mockk()
        requestSpec = mockk()
        callResponseSpec = mockk()

        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns callResponseSpec
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `should compress document and replace content`() = runTest {
            val longContent = "A".repeat(300)
            val extracted = "Only relevant part"
            mockLlmResponse(extracted)

            val compressor = LlmContextualCompressor(chatClient)
            val docs = listOf(
                RetrievedDocument(
                    id = "doc-1",
                    content = longContent,
                    metadata = mapOf("source" to "test"),
                    score = 0.9
                )
            )

            val result = compressor.compress("query", docs)

            assertEquals(1, result.size) { "Should return 1 compressed document" }
            assertEquals(extracted, result[0].content) { "Content should be compressed extract" }
            assertEquals("doc-1", result[0].id) { "Document ID should be preserved" }
            assertEquals(0.9, result[0].score) { "Score should be preserved" }
            assertEquals(
                mapOf("source" to "test"),
                result[0].metadata
            ) { "Metadata should be preserved" }
        }

        @Test
        fun `should remove irrelevant documents`() = runTest {
            mockLlmResponse("IRRELEVANT")

            val compressor = LlmContextualCompressor(chatClient)
            val docs = listOf(
                RetrievedDocument(id = "doc-1", content = "A".repeat(300))
            )

            val result = compressor.compress("query", docs)

            assertTrue(result.isEmpty()) { "Irrelevant document should be removed" }
        }

        @Test
        fun `should handle case-insensitive IRRELEVANT response`() = runTest {
            mockLlmResponse("irrelevant")

            val compressor = LlmContextualCompressor(chatClient)
            val docs = listOf(
                RetrievedDocument(id = "doc-1", content = "A".repeat(300))
            )

            val result = compressor.compress("query", docs)

            assertTrue(result.isEmpty()) { "Case-insensitive 'irrelevant' should remove document" }
        }

        @Test
        fun `should compress multiple documents in parallel`() = runTest {
            val assistantMessage = AssistantMessage("compressed content")
            val chatResponse = ChatResponse(listOf(Generation(assistantMessage)))
            every { callResponseSpec.chatResponse() } returns chatResponse

            val compressor = LlmContextualCompressor(chatClient)
            val docs = listOf(
                RetrievedDocument(id = "doc-1", content = "A".repeat(300)),
                RetrievedDocument(id = "doc-2", content = "B".repeat(300)),
                RetrievedDocument(id = "doc-3", content = "C".repeat(300))
            )

            val result = compressor.compress("query", docs)

            assertEquals(3, result.size) { "All 3 documents should be compressed" }
        }

        @Test
        fun `should return empty list for empty input`() = runTest {
            val compressor = LlmContextualCompressor(chatClient)

            val result = compressor.compress("query", emptyList())

            assertTrue(result.isEmpty()) { "Empty input should produce empty output" }
        }
    }

    @Nested
    inner class ShortDocumentSkip {

        @Test
        fun `should skip compression for short documents`() = runTest {
            val shortContent = "Short text"
            val compressor = LlmContextualCompressor(chatClient)
            val docs = listOf(
                RetrievedDocument(id = "doc-1", content = shortContent)
            )

            val result = compressor.compress("query", docs)

            assertEquals(1, result.size) { "Short document should be passed through" }
            assertEquals(shortContent, result[0].content) { "Short document content should be unchanged" }
            verify(exactly = 0) { chatClient.prompt() }
        }

        @Test
        fun `should respect custom minContentLength`() = runTest {
            val content = "A".repeat(100)
            mockLlmResponse("compressed")

            val compressor = LlmContextualCompressor(chatClient, minContentLength = 50)
            val docs = listOf(
                RetrievedDocument(id = "doc-1", content = content)
            )

            val result = compressor.compress("query", docs)

            assertEquals("compressed", result[0].content) {
                "Document exceeding custom minContentLength should be compressed"
            }
            verify(exactly = 1) { chatClient.prompt() }
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `should keep original document when LLM returns null`() = runTest {
            every { callResponseSpec.chatResponse() } returns null

            val originalContent = "A".repeat(300)
            val compressor = LlmContextualCompressor(chatClient)
            val docs = listOf(
                RetrievedDocument(id = "doc-1", content = originalContent)
            )

            val result = compressor.compress("query", docs)

            assertEquals(1, result.size) { "Should keep document on null response" }
            assertEquals(originalContent, result[0].content) {
                "Content should be original on null response"
            }
        }

        @Test
        fun `should keep original document when LLM returns blank`() = runTest {
            mockLlmResponse("   ")

            val originalContent = "A".repeat(300)
            val compressor = LlmContextualCompressor(chatClient)
            val docs = listOf(
                RetrievedDocument(id = "doc-1", content = originalContent)
            )

            val result = compressor.compress("query", docs)

            assertEquals(1, result.size) { "Should keep document on blank response" }
            assertEquals(originalContent, result[0].content) {
                "Content should be original on blank response"
            }
        }

        @Test
        fun `should keep original document when LLM throws exception`() = runTest {
            every { requestSpec.call() } throws RuntimeException("LLM unavailable")

            val originalContent = "A".repeat(300)
            val compressor = LlmContextualCompressor(chatClient)
            val docs = listOf(
                RetrievedDocument(id = "doc-1", content = originalContent)
            )

            val result = compressor.compress("query", docs)

            assertEquals(1, result.size) { "Should keep document on exception" }
            assertEquals(originalContent, result[0].content) {
                "Content should be original on exception"
            }
        }

        @Test
        fun `should propagate CancellationException`() = runTest {
            every { requestSpec.call() } throws java.util.concurrent.CancellationException("cancelled")

            val compressor = LlmContextualCompressor(chatClient)
            val docs = listOf(
                RetrievedDocument(id = "doc-1", content = "A".repeat(300))
            )

            assertThrows<java.util.concurrent.CancellationException> {
                compressor.compress("query", docs)
            }
        }
    }

    @Nested
    inner class MixedDocuments {

        @Test
        fun `should pass through short document without LLM call`() = runTest {
            mockLlmResponse("IRRELEVANT")

            val compressor = LlmContextualCompressor(chatClient)
            val docs = listOf(
                RetrievedDocument(id = "doc-short", content = "Short"),
                RetrievedDocument(id = "doc-long", content = "A".repeat(300))
            )

            val result = compressor.compress("query", docs)

            assertEquals(1, result.size) { "Should have 1 doc: short passed through, long removed" }
            assertEquals("doc-short", result[0].id) { "Short document should be preserved" }
            assertEquals("Short", result[0].content) { "Short document content should be unchanged" }
        }
    }

    private fun mockLlmResponse(text: String) {
        val assistantMessage = AssistantMessage(text)
        val chatResponse = ChatResponse(listOf(Generation(assistantMessage)))
        every { callResponseSpec.chatResponse() } returns chatResponse
    }
}
