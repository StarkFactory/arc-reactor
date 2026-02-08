package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ResponseFormat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for ChatController.
 *
 * Tests request-to-command mapping and response construction
 * without Spring context (pure unit test with MockK).
 */
class ChatControllerTest {

    private lateinit var agentExecutor: AgentExecutor
    private lateinit var controller: ChatController

    @BeforeEach
    fun setup() {
        agentExecutor = mockk()
        controller = ChatController(agentExecutor)
    }

    @Nested
    inner class StandardChat {

        @Test
        fun `should return successful response with content`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success(
                content = "Hello!",
                toolsUsed = listOf("calculator")
            )

            val request = ChatRequest(message = "Hi there")
            val response = controller.chat(request)

            assertTrue(response.success) { "Response should be successful" }
            assertEquals("Hello!", response.content) { "Content should match agent result" }
            assertEquals(listOf("calculator"), response.toolsUsed) { "Tools used should be forwarded" }
            assertNull(response.errorMessage) { "Error message should be null on success" }
        }

        @Test
        fun `should map request fields to AgentCommand correctly`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val request = ChatRequest(
                message = "test message",
                model = "gpt-4o",
                systemPrompt = "custom prompt",
                userId = "user-123",
                metadata = mapOf("key" to "value"),
                responseFormat = ResponseFormat.JSON,
                responseSchema = """{"type":"object"}"""
            )
            controller.chat(request)

            val captured = commandSlot.captured
            assertEquals("test message", captured.userPrompt) { "userPrompt should match request message" }
            assertEquals("gpt-4o", captured.model) { "model should be forwarded" }
            assertEquals("custom prompt", captured.systemPrompt) { "custom systemPrompt should be used" }
            assertEquals("user-123", captured.userId) { "userId should be forwarded" }
            assertEquals(mapOf("key" to "value"), captured.metadata) { "metadata should be forwarded" }
            assertEquals(ResponseFormat.JSON, captured.responseFormat) { "responseFormat should be forwarded" }
            assertEquals("""{"type":"object"}""", captured.responseSchema) { "responseSchema should be forwarded" }
        }

        @Test
        fun `should use default system prompt when not provided`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"))

            assertTrue(commandSlot.captured.systemPrompt.contains("helpful AI assistant")) {
                "Default system prompt should contain 'helpful AI assistant'"
            }
        }

        @Test
        fun `should use default metadata when not provided`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"))

            assertEquals(emptyMap<String, Any>(), commandSlot.captured.metadata) {
                "Default metadata should be empty map"
            }
        }

        @Test
        fun `should return failure response with error message`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns AgentResult.failure(
                errorMessage = "Rate limit exceeded"
            )

            val response = controller.chat(ChatRequest(message = "hello"))

            assertFalse(response.success) { "Response should indicate failure" }
            assertNull(response.content) { "Content should be null on failure" }
            assertEquals("Rate limit exceeded", response.errorMessage) { "Error message should be forwarded" }
        }

        @Test
        fun `should forward model in response`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns AgentResult.success("ok")

            val response = controller.chat(ChatRequest(message = "hi", model = "gemini-2.0-flash"))

            assertEquals("gemini-2.0-flash", response.model) { "Model should be forwarded in response" }
        }
    }

    @Nested
    inner class StreamingChat {

        @Test
        fun `should return streaming flux from agent executor`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.executeStream(capture(commandSlot)) } returns flowOf("Hello", " ", "World")

            val request = ChatRequest(message = "Hi")
            val flow = agentExecutor.executeStream(
                AgentCommand(
                    systemPrompt = "You are a helpful AI assistant. You can use tools when needed. Answer in the same language as the user's message.",
                    userPrompt = "Hi"
                )
            )
            val chunks = flow.toList()

            assertEquals(listOf("Hello", " ", "World"), chunks) { "Should stream all chunks" }
        }

        @Test
        fun `should map streaming request fields to AgentCommand`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.executeStream(capture(commandSlot)) } returns flowOf("ok")

            val request = ChatRequest(
                message = "stream test",
                model = "gpt-4o",
                userId = "user-456"
            )
            controller.chatStream(request)

            val captured = commandSlot.captured
            assertEquals("stream test", captured.userPrompt) { "userPrompt should match" }
            assertEquals("gpt-4o", captured.model) { "model should be forwarded" }
            assertEquals("user-456", captured.userId) { "userId should be forwarded" }
        }

        @Test
        fun `should use default values for optional streaming fields`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.executeStream(capture(commandSlot)) } returns flowOf("ok")

            controller.chatStream(ChatRequest(message = "hello"))

            val captured = commandSlot.captured
            assertTrue(captured.systemPrompt.contains("helpful AI assistant")) {
                "Default system prompt should be applied"
            }
            assertEquals(ResponseFormat.TEXT, captured.responseFormat) {
                "Default response format should be TEXT"
            }
            assertEquals(emptyMap<String, Any>(), captured.metadata) {
                "Default metadata should be empty"
            }
        }
    }

    @Nested
    inner class RequestDefaults {

        @Test
        fun `ChatRequest should have sensible defaults`() {
            val request = ChatRequest(message = "test")

            assertNull(request.model) { "model should default to null" }
            assertNull(request.systemPrompt) { "systemPrompt should default to null" }
            assertNull(request.userId) { "userId should default to null" }
            assertNull(request.metadata) { "metadata should default to null" }
            assertNull(request.responseFormat) { "responseFormat should default to null" }
            assertNull(request.responseSchema) { "responseSchema should default to null" }
        }

        @Test
        fun `ChatResponse should include all fields`() {
            val response = ChatResponse(
                content = "result",
                success = true,
                model = "gemini",
                toolsUsed = listOf("calc"),
                errorMessage = null
            )

            assertEquals("result", response.content) { "content should match" }
            assertTrue(response.success) { "success should be true" }
            assertEquals("gemini", response.model) { "model should match" }
            assertEquals(listOf("calc"), response.toolsUsed) { "toolsUsed should match" }
            assertNull(response.errorMessage) { "errorMessage should be null" }
        }
    }
}
