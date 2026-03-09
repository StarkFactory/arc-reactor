package com.arc.reactor.rag

import com.arc.reactor.rag.impl.ConversationAwareQueryTransformer
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

class ConversationAwareQueryTransformerTest {

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
    inner class WithConversationHistory {

        @Test
        fun `should rewrite query with conversation context`() = runTest {
            mockLlmResponse("What is the return policy for electronics?")

            val transformer = ConversationAwareQueryTransformer(chatClient)
            val result = transformer.transformWithHistory(
                "What about electronics?",
                listOf(
                    "User: Tell me about the return policy",
                    "AI: Items can be returned within 30 days."
                )
            )

            assertEquals(1, result.size) { "Should return rewritten query" }
            assertEquals("What is the return policy for electronics?", result[0]) {
                "Should resolve 'what about' into a standalone query"
            }
        }

        @Test
        fun `should pass conversation history to LLM`() = runTest {
            mockLlmResponse("standalone query")
            val historySlot = slot<String>()

            every { requestSpec.user(capture(historySlot)) } returns requestSpec

            val transformer = ConversationAwareQueryTransformer(chatClient)
            transformer.transformWithHistory("What about it?", listOf("User: Hello", "AI: Hi there"))

            assertTrue(historySlot.captured.contains("User: Hello")) {
                "LLM input should contain conversation history"
            }
            assertTrue(historySlot.captured.contains("What about it?")) {
                "LLM input should contain current query"
            }
        }

        @Test
        fun `should limit history to maxHistoryTurns`() = runTest {
            mockLlmResponse("rewritten query")
            val historySlot = slot<String>()
            every { requestSpec.user(capture(historySlot)) } returns requestSpec

            val transformer = ConversationAwareQueryTransformer(chatClient, maxHistoryTurns = 2)
            transformer.transformWithHistory("query", listOf("Turn 1", "Turn 2", "Turn 3", "Turn 4"))

            // Only last 2 turns should be included
            assertFalse(historySlot.captured.contains("Turn 1")) {
                "Old history turns should be trimmed"
            }
            assertTrue(historySlot.captured.contains("Turn 3")) {
                "Recent turns should be included"
            }
            assertTrue(historySlot.captured.contains("Turn 4")) {
                "Most recent turn should be included"
            }
        }
    }

    @Nested
    inner class WithoutConversationHistory {

        @Test
        fun `should return original query when no history via transform`() = runTest {
            val transformer = ConversationAwareQueryTransformer(chatClient)
            val result = transformer.transform("What is the return policy?")

            assertEquals(1, result.size) { "Should return original query" }
            assertEquals("What is the return policy?", result[0]) { "Should be unchanged" }
            // Should NOT call LLM when using transform() directly
            verify(exactly = 0) { chatClient.prompt() }
        }

        @Test
        fun `should return original query when history is empty list`() = runTest {
            val transformer = ConversationAwareQueryTransformer(chatClient)
            val result = transformer.transformWithHistory("standalone query", emptyList())

            assertEquals(listOf("standalone query"), result) { "Should return original query unchanged" }
            verify(exactly = 0) { chatClient.prompt() }
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `should fallback to original query when LLM throws exception`() = runTest {
            every { requestSpec.call() } throws RuntimeException("LLM unavailable")

            val transformer = ConversationAwareQueryTransformer(chatClient)
            val result = transformer.transformWithHistory("original query", listOf("some history"))

            assertEquals(listOf("original query"), result) { "Should fallback to original on error" }
        }

        @Test
        fun `should fallback to original query when LLM returns null`() = runTest {
            every { callResponseSpec.chatResponse() } returns null

            val transformer = ConversationAwareQueryTransformer(chatClient)
            val result = transformer.transformWithHistory("original query", listOf("some history"))

            assertEquals(listOf("original query"), result) { "Should fallback to original on null" }
        }

        @Test
        fun `should return original when LLM returns same query`() = runTest {
            mockLlmResponse("already standalone query")

            val transformer = ConversationAwareQueryTransformer(chatClient)
            val result = transformer.transformWithHistory("already standalone query", listOf("some history"))

            assertEquals(1, result.size) { "Should return single query" }
            assertEquals("already standalone query", result[0]) { "Should be the original" }
        }

        @Test
        fun `should fallback to original query when LLM returns blank`() = runTest {
            mockLlmResponse("   ")

            val transformer = ConversationAwareQueryTransformer(chatClient)
            val result = transformer.transformWithHistory("original query", listOf("some history"))

            assertEquals(listOf("original query"), result) { "Should fallback to original on blank response" }
        }

        @Test
        fun `should propagate CancellationException for structured concurrency`() = runTest {
            every { requestSpec.call() } throws java.util.concurrent.CancellationException("cancelled")

            val transformer = ConversationAwareQueryTransformer(chatClient)

            assertThrows<java.util.concurrent.CancellationException> {
                transformer.transformWithHistory("query", listOf("some history"))
            }
        }
    }

    @Nested
    inner class IndependentRequests {

        @Test
        fun `sequential requests with different histories should not interfere`() = runTest {
            val transformer = ConversationAwareQueryTransformer(chatClient)

            val userSlot = slot<String>()
            every { requestSpec.user(capture(userSlot)) } returns requestSpec

            // Return different rewrites based on the history content
            every { callResponseSpec.chatResponse() } answers {
                val captured = userSlot.captured
                val rewritten = when {
                    captured.contains("about dogs") -> "return policy for dogs"
                    captured.contains("about cats") -> "return policy for cats"
                    else -> "standalone query"
                }
                ChatResponse(listOf(Generation(AssistantMessage(rewritten))))
            }

            // First request with dogs history
            val result1 = transformer.transformWithHistory(
                "What about the return policy?",
                listOf("User: Tell me about dogs")
            )

            // Second request with cats history — completely independent
            val result2 = transformer.transformWithHistory(
                "What about the return policy?",
                listOf("User: Tell me about cats")
            )

            assertEquals(listOf("return policy for dogs"), result1) {
                "First request should use dogs history"
            }
            assertEquals(listOf("return policy for cats"), result2) {
                "Second request should use cats history, not dogs"
            }
        }

        @Test
        fun `calls without history should never invoke LLM`() = runTest {
            mockLlmResponse("rewritten query")

            val transformer = ConversationAwareQueryTransformer(chatClient)
            transformer.transformWithHistory("query", listOf("some history"))

            // Subsequent call with no history should skip LLM entirely
            val result = transformer.transformWithHistory("another query", emptyList())

            assertEquals(listOf("another query"), result) {
                "Call with empty history should return original without invoking LLM"
            }
            // LLM should have been called only once (for the first transformWithHistory call)
            verify(atMost = 1) { chatClient.prompt() }
        }
    }

    private fun mockLlmResponse(text: String) {
        val assistantMessage = AssistantMessage(text)
        val chatResponse = ChatResponse(listOf(Generation(assistantMessage)))
        every { callResponseSpec.chatResponse() } returns chatResponse
    }
}
