package com.arc.reactor.memory.summary

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

class LlmConversationSummaryServiceTest {

    @Nested
    inner class ParseResponse {

        private val service = LlmConversationSummaryService(
            chatClient = mockk(),
            maxNarrativeTokens = 500
        )

        @Test
        fun `should parse valid JSON response`() {
            val json = """
                {
                  "narrative": "User asked about refund for order #1234.",
                  "facts": [
                    {"key": "order_number", "value": "#1234", "category": "ENTITY"},
                    {"key": "refund_amount", "value": "50000 KRW", "category": "NUMERIC"}
                  ]
                }
            """.trimIndent()

            val result = service.parseResponse(json)

            assertEquals("User asked about refund for order #1234.", result.narrative,
                "Narrative should be parsed correctly")
            assertEquals(2, result.facts.size, "Should extract 2 facts")
            assertEquals("order_number", result.facts[0].key, "First fact key should match")
            assertEquals("#1234", result.facts[0].value, "First fact value should match")
            assertEquals(FactCategory.ENTITY, result.facts[0].category, "First fact category should match")
            assertEquals(FactCategory.NUMERIC, result.facts[1].category, "Second fact category should match")
        }

        @Test
        fun `should strip code fences before parsing`() {
            val json = """
                ```json
                {"narrative": "test", "facts": []}
                ```
            """.trimIndent()

            val result = service.parseResponse(json)

            assertEquals("test", result.narrative, "Should parse after stripping code fences")
            assertTrue(result.facts.isEmpty(), "Facts should be empty")
        }

        @Test
        fun `should fallback to raw text when JSON is invalid`() {
            val invalidJson = "This is not JSON but a useful summary"

            val result = service.parseResponse(invalidJson)

            assertEquals("This is not JSON but a useful summary", result.narrative,
                "Should use raw text as narrative on parse failure")
            assertTrue(result.facts.isEmpty(), "Facts should be empty on parse failure")
        }

        @Test
        fun `should handle unknown category gracefully`() {
            val json = """
                {
                  "narrative": "test",
                  "facts": [{"key": "k", "value": "v", "category": "UNKNOWN_CATEGORY"}]
                }
            """.trimIndent()

            val result = service.parseResponse(json)

            assertEquals(FactCategory.GENERAL, result.facts[0].category,
                "Unknown category should default to GENERAL")
        }

        @Test
        fun `should handle missing fields with defaults`() {
            val json = """{"narrative": "test"}"""

            val result = service.parseResponse(json)

            assertEquals("test", result.narrative, "Narrative should be parsed")
            assertTrue(result.facts.isEmpty(), "Missing facts should default to empty")
        }
    }

    @Nested
    inner class StripCodeFences {

        @Test
        fun `should strip json code fences`() {
            val input = "```json\n{\"key\": \"value\"}\n```"
            assertEquals("{\"key\": \"value\"}", LlmConversationSummaryService.stripCodeFences(input),
                "Should strip json code fences")
        }

        @Test
        fun `should strip plain code fences`() {
            val input = "```\n{\"key\": \"value\"}\n```"
            assertEquals("{\"key\": \"value\"}", LlmConversationSummaryService.stripCodeFences(input),
                "Should strip plain code fences")
        }

        @Test
        fun `should return unchanged text without code fences`() {
            val input = "{\"key\": \"value\"}"
            assertEquals("{\"key\": \"value\"}", LlmConversationSummaryService.stripCodeFences(input),
                "Should return text unchanged without code fences")
        }
    }

    @Nested
    inner class Summarize {

        @Test
        fun `should return empty result for empty messages`() = runTest {
            val service = LlmConversationSummaryService(chatClient = mockk(), maxNarrativeTokens = 500)

            val result = service.summarize(emptyList())

            assertEquals("", result.narrative, "Empty messages should produce empty narrative")
            assertTrue(result.facts.isEmpty(), "Empty messages should produce empty facts")
        }

        @Test
        fun `should call LLM and parse response`() = runTest {
            val chatClient = mockk<ChatClient>()
            val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callResponseSpec = mockk<ChatClient.CallResponseSpec>()

            val responseJson = """
                {"narrative": "Discussed order.", "facts": [{"key": "id", "value": "123", "category": "ENTITY"}]}
            """.trimIndent()

            every { chatClient.prompt() } returns requestSpec
            every { requestSpec.system(any<String>()) } returns requestSpec
            every { requestSpec.user(any<String>()) } returns requestSpec
            every { requestSpec.call() } returns callResponseSpec
            every { callResponseSpec.chatResponse() } returns ChatResponse(
                listOf(Generation(AssistantMessage(responseJson)))
            )

            val service = LlmConversationSummaryService(chatClient = chatClient, maxNarrativeTokens = 500)
            val messages = listOf(
                Message(MessageRole.USER, "What is my order status?"),
                Message(MessageRole.ASSISTANT, "Your order #123 is being shipped.")
            )

            val result = service.summarize(messages)

            assertEquals("Discussed order.", result.narrative, "Narrative should be parsed from LLM response")
            assertEquals(1, result.facts.size, "Should extract one fact")
            assertEquals("123", result.facts[0].value, "Fact value should match")
        }
    }
}
