package com.arc.reactor.rag

import com.arc.reactor.rag.impl.HyDEQueryTransformer
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

class HyDEQueryTransformerTest {

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
        fun `should return original query and hypothetical document`() = runTest {
            val hypothetical = "Our return policy allows returns within 30 days of purchase."
            mockLlmResponse(hypothetical)

            val transformer = HyDEQueryTransformer(chatClient)
            val result = transformer.transform("What is the return policy?")

            assertEquals(2, result.size) { "Should return original + hypothetical" }
            assertEquals("What is the return policy?", result[0]) { "First should be original query" }
            assertEquals(hypothetical, result[1]) { "Second should be hypothetical document" }
        }

        @Test
        fun `should use custom system prompt`() = runTest {
            val customPrompt = "Generate a FAQ answer."
            mockLlmResponse("Some answer.")

            val transformer = HyDEQueryTransformer(chatClient, systemPrompt = customPrompt)
            transformer.transform("query")

            verify { requestSpec.system(customPrompt) }
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `should fallback to original query when LLM returns null`() = runTest {
            every { callResponseSpec.chatResponse() } returns null

            val transformer = HyDEQueryTransformer(chatClient)
            val result = transformer.transform("original query")

            assertEquals(1, result.size) { "Should fallback to original query only" }
            assertEquals("original query", result[0]) { "Should be the original query" }
        }

        @Test
        fun `should fallback to original query when LLM returns blank`() = runTest {
            mockLlmResponse("   ")

            val transformer = HyDEQueryTransformer(chatClient)
            val result = transformer.transform("original query")

            assertEquals(1, result.size) { "Should fallback to original query only" }
            assertEquals("original query", result[0]) { "Should be the original query" }
        }

        @Test
        fun `should fallback to original query when LLM throws exception`() = runTest {
            every { requestSpec.call() } throws RuntimeException("LLM unavailable")

            val transformer = HyDEQueryTransformer(chatClient)
            val result = transformer.transform("original query")

            assertEquals(1, result.size) { "Should fallback to original query only" }
            assertEquals("original query", result[0]) { "Should be the original query" }
        }

        @Test
        fun `should propagate CancellationException for structured concurrency`() = runTest {
            every { requestSpec.call() } throws java.util.concurrent.CancellationException("cancelled")

            val transformer = HyDEQueryTransformer(chatClient)

            assertThrows<java.util.concurrent.CancellationException> {
                transformer.transform("query")
            }
        }
    }

    private fun mockLlmResponse(text: String) {
        val assistantMessage = AssistantMessage(text)
        val chatResponse = ChatResponse(listOf(Generation(assistantMessage)))
        every { callResponseSpec.chatResponse() } returns chatResponse
    }
}
