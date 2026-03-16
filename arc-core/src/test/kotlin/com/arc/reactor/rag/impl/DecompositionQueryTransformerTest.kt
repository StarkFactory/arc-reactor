package com.arc.reactor.rag.impl

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

class DecompositionQueryTransformerTest {

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
        fun `decompose complex query into sub-queries plus original해야 한다`() = runTest {
            val subQueries = "What is our return policy?\nWhat are competitor return policies?"
            mockLlmResponse(subQueries)

            val transformer = DecompositionQueryTransformer(chatClient)
            val result = transformer.transform("How does our return policy compare to competitors?")

            assertEquals(3, result.size) { "Should return sub-queries + original query" }
            assertEquals("What is our return policy?", result[0]) { "First sub-query" }
            assertEquals("What are competitor return policies?", result[1]) { "Second sub-query" }
            assertEquals(
                "How does our return policy compare to competitors?",
                result[2]
            ) { "Original query should be included" }
        }

        @Test
        fun `LLM returns original query as sub-query일 때 deduplicate해야 한다`() = runTest {
            mockLlmResponse("What is the return policy?")

            val transformer = DecompositionQueryTransformer(chatClient)
            val result = transformer.transform("What is the return policy?")

            assertEquals(1, result.size) { "Should deduplicate original query" }
            assertEquals("What is the return policy?", result[0]) { "Should contain original query" }
        }

        @Test
        fun `filter blank lines in LLM response해야 한다`() = runTest {
            mockLlmResponse("Sub query 1\n\n\nSub query 2\n\n")

            val transformer = DecompositionQueryTransformer(chatClient)
            val result = transformer.transform("complex query")

            assertEquals(3, result.size) { "Should have 2 sub-queries + original" }
            assertTrue(result.contains("Sub query 1")) { "Should contain first sub-query" }
            assertTrue(result.contains("Sub query 2")) { "Should contain second sub-query" }
            assertTrue(result.contains("complex query")) { "Should contain original query" }
        }

        @Test
        fun `decomposition에 대해 use system prompt해야 한다`() = runTest {
            mockLlmResponse("simple query")

            val transformer = DecompositionQueryTransformer(chatClient)
            transformer.transform("test query")

            verify { requestSpec.system(DecompositionQueryTransformer.SYSTEM_PROMPT) }
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `LLM returns null일 때 fallback to original query해야 한다`() = runTest {
            every { callResponseSpec.chatResponse() } returns null

            val transformer = DecompositionQueryTransformer(chatClient)
            val result = transformer.transform("original query")

            assertEquals(1, result.size) { "Should fallback to original query only" }
            assertEquals("original query", result[0]) { "Should be the original query" }
        }

        @Test
        fun `LLM returns blank일 때 fallback to original query해야 한다`() = runTest {
            mockLlmResponse("   ")

            val transformer = DecompositionQueryTransformer(chatClient)
            val result = transformer.transform("original query")

            assertEquals(1, result.size) { "Should fallback to original query only" }
            assertEquals("original query", result[0]) { "Should be the original query" }
        }

        @Test
        fun `LLM throws exception일 때 fallback to original query해야 한다`() = runTest {
            every { requestSpec.call() } throws RuntimeException("LLM unavailable")

            val transformer = DecompositionQueryTransformer(chatClient)
            val result = transformer.transform("original query")

            assertEquals(1, result.size) { "Should fallback to original query only" }
            assertEquals("original query", result[0]) { "Should be the original query" }
        }

        @Test
        fun `structured concurrency에 대해 propagate CancellationException해야 한다`() = runTest {
            every { requestSpec.call() } throws java.util.concurrent.CancellationException("cancelled")

            val transformer = DecompositionQueryTransformer(chatClient)

            assertThrows<java.util.concurrent.CancellationException> {
                transformer.transform("query")
            }
        }
    }

    @Nested
    inner class ParseSubQueries {

        @Test
        fun `parse multi-line response해야 한다`() {
            val result = DecompositionQueryTransformer.parseSubQueries("line1\nline2\nline3")
            assertEquals(3, result.size) { "Should parse 3 lines" }
        }

        @Test
        fun `null input에 대해 return empty해야 한다`() {
            val result = DecompositionQueryTransformer.parseSubQueries(null)
            assertTrue(result.isEmpty()) { "Should return empty list for null" }
        }

        @Test
        fun `blank input에 대해 return empty해야 한다`() {
            val result = DecompositionQueryTransformer.parseSubQueries("  \n  \n  ")
            assertTrue(result.isEmpty()) { "Should return empty list for blank" }
        }

        @Test
        fun `trim whitespace from each line해야 한다`() {
            val result = DecompositionQueryTransformer.parseSubQueries("  query 1  \n  query 2  ")
            assertEquals("query 1", result[0]) { "Should trim first line" }
            assertEquals("query 2", result[1]) { "Should trim second line" }
        }
    }

    private fun mockLlmResponse(text: String) {
        val assistantMessage = AssistantMessage(text)
        val chatResponse = ChatResponse(listOf(Generation(assistantMessage)))
        every { callResponseSpec.chatResponse() } returns chatResponse
    }
}
