package com.arc.reactor.agent

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.config.GuardProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.memory.InMemoryMemoryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

class SpringAiAgentExecutorTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class BasicExecution {

        @Test
        fun `should execute simple command successfully`() = runBlocking {
            // Arrange
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Hello! How can I help you?")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // Assert
            result.assertSuccess()
            assertEquals("Hello! How can I help you?", result.content)
            assertTrue(result.durationMs >= 0) { "Expected non-negative durationMs, got: ${result.durationMs}" }
        }

        @Test
        fun `should extract token usage from response`() = runBlocking {
            // Arrange
            val usage = mockk<Usage>()
            every { usage.promptTokens } returns 100
            every { usage.completionTokens } returns 50
            every { usage.totalTokens } returns 150

            val metadata = mockk<ChatResponseMetadata>()
            every { metadata.usage } returns usage

            val chatResponse = mockk<ChatResponse>()
            every { chatResponse.metadata } returns metadata
            every { chatResponse.results } returns emptyList()

            every { fixture.callResponseSpec.content() } returns "Response"
            every { fixture.callResponseSpec.chatResponse() } returns chatResponse

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // Assert
            result.assertSuccess()
            val tokenUsage = requireNotNull(result.tokenUsage)
            assertEquals(100, tokenUsage.promptTokens)
            assertEquals(50, tokenUsage.completionTokens)
            assertEquals(150, tokenUsage.totalTokens)
        }
    }

    @Nested
    inner class GuardIntegration {

        @Test
        fun `should reject when guard fails`() = runBlocking {
            // Arrange
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any()) } returns GuardResult.Rejected(
                reason = "Rate limit exceeded",
                category = RejectionCategory.RATE_LIMITED,
                stage = "rateLimit"
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                guard = guard
            )

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!",
                    userId = "user-123"
                )
            )

            // Assert
            result.assertFailure()
            assertEquals("Rate limit exceeded", result.errorMessage)
            result.assertErrorCode(AgentErrorCode.GUARD_REJECTED)
        }

        @Test
        fun `should run guard with anonymous userId when userId is null`() = runBlocking {
            // Arrange
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any()) } returns GuardResult.Allowed.DEFAULT
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                guard = guard
            )

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!",
                    userId = null  // No userId — should still pass through guard as "anonymous"
                )
            )

            // Assert
            result.assertSuccess()
            coVerify(exactly = 1) { guard.guard(match { it.userId == "anonymous" }) }
        }
    }

    @Nested
    inner class HookIntegration {

        @Test
        fun `should execute hooks in correct order`() = runBlocking {
            // Arrange
            val executionOrder = mutableListOf<String>()

            val beforeHook = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    executionOrder.add("before")
                    return HookResult.Continue
                }
            }

            val afterHook = object : AfterAgentCompleteHook {
                override val order = 1
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    executionOrder.add("after")
                }
            }

            val hookExecutor = HookExecutor(
                beforeStartHooks = listOf(beforeHook),
                afterCompleteHooks = listOf(afterHook)
            )

            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            // Act
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // Assert
            assertEquals(listOf("before", "after"), executionOrder)
        }

        @Test
        fun `should reject when beforeAgentStart hook rejects`() = runBlocking {
            // Arrange
            val rejectHook = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    return HookResult.Reject("Not allowed")
                }
            }

            val hookExecutor = HookExecutor(beforeStartHooks = listOf(rejectHook))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // Assert
            result.assertFailure()
            assertEquals("Not allowed", result.errorMessage)
            result.assertErrorCode(AgentErrorCode.HOOK_REJECTED)
        }

        @Test
        fun `should preserve success result when afterAgentComplete hook throws`() = runBlocking {
            // Arrange
            val throwingAfterHook = object : AfterAgentCompleteHook {
                override val order = 1
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    throw RuntimeException("Hook explosion!")
                }
            }

            val hookExecutor = HookExecutor(afterCompleteHooks = listOf(throwingAfterHook))

            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Successful response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // Assert - result should be success despite hook throwing
            assertTrue(result.success, "Hook exception should not mask successful result")
            assertEquals("Successful response", result.content)
        }
    }

    @Nested
    inner class MemoryIntegration {

        @Test
        fun `should save conversation to memory`() = runBlocking {
            // Arrange
            val memoryStore = InMemoryMemoryStore()
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Hello there!")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore
            )

            // Act
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hi!",
                    metadata = mapOf("sessionId" to "session-123")
                )
            )

            // Assert
            val memory = memoryStore.get("session-123")
            assertNotNull(memory) { "Memory for session-123 should exist after execute, got null" }
            assertEquals(2, memory!!.getHistory().size)
            assertEquals("Hi!", memory.getHistory()[0].content)
            assertEquals("Hello there!", memory.getHistory()[1].content)
        }

        @Test
        fun `should load conversation history from memory`() = runBlocking {
            // Arrange
            val memoryStore = InMemoryMemoryStore()
            memoryStore.addMessage("session-123", "user", "Previous question")
            memoryStore.addMessage("session-123", "assistant", "Previous answer")

            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("New response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore
            )

            // Act
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Follow up question",
                    metadata = mapOf("sessionId" to "session-123")
                )
            )

            // Assert
            coVerify { fixture.requestSpec.messages(any<List<org.springframework.ai.chat.messages.Message>>()) }
        }

        @Test
        fun `should save memory with non-String sessionId type`() = runBlocking {
            // Arrange
            val memoryStore = InMemoryMemoryStore()
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore
            )

            // Act - sessionId is an Integer, not String
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hi!",
                    metadata = mapOf("sessionId" to 12345)
                )
            )

            // Assert - should be saved via ?.toString()
            val memory = memoryStore.get("12345")
            assertNotNull(memory, "Memory should be saved even with non-String sessionId")
            assertEquals(2, memory!!.getHistory().size)
        }
    }

    @Nested
    inner class ErrorHandling {
        // Error translation behavior does not require retry/backoff; disable retries to keep tests fast.
        private val noRetryProperties = properties.copy(
            retry = properties.retry.copy(
                maxAttempts = 1,
                initialDelayMs = 1,
                maxDelayMs = 1
            )
        )

        @Test
        fun `should handle LLM exception gracefully`() = runBlocking {
            // Arrange
            every { fixture.requestSpec.call() } throws RuntimeException("LLM service unavailable")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = noRetryProperties
            )

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // Assert
            result.assertFailure()
            assertNotNull(result.errorMessage) { "LLM exception should produce an error message" }
        }

        @Test
        fun `should translate rate limit error`() = runBlocking {
            // Arrange
            every { fixture.requestSpec.call() } throws RuntimeException("Rate limit exceeded")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = noRetryProperties
            )

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // Assert
            result.assertFailure()
            result.assertErrorCode(AgentErrorCode.RATE_LIMITED)
            assertTrue(result.errorMessage!!.contains("Rate limit exceeded")) {
                "Expected error to contain 'Rate limit exceeded', got: ${result.errorMessage}"
            }
        }

        @Test
        fun `should translate timeout error`() = runBlocking {
            // Arrange
            every { fixture.requestSpec.call() } throws RuntimeException("Connection timeout")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = noRetryProperties
            )

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // Assert
            result.assertFailure()
            result.assertErrorCode(AgentErrorCode.TIMEOUT)
            assertTrue(result.errorMessage!!.contains("Request timed out")) {
                "Expected error to contain 'Request timed out', got: ${result.errorMessage}"
            }
        }

        @Test
        fun `should use custom error message resolver`() = runBlocking {
            // Arrange
            every { fixture.requestSpec.call() } throws RuntimeException("Rate limit exceeded")

            val koreanResolver = ErrorMessageResolver { code, _ ->
                when (code) {
                    AgentErrorCode.RATE_LIMITED -> "요청 한도를 초과했습니다."
                    AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다."
                    else -> code.defaultMessage
                }
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = noRetryProperties,
                errorMessageResolver = koreanResolver
            )

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // Assert
            result.assertFailure()
            result.assertErrorCode(AgentErrorCode.RATE_LIMITED)
            assertTrue(result.errorMessage!!.contains("요청 한도")) {
                "Expected Korean rate limit message containing '요청 한도', got: ${result.errorMessage}"
            }
        }
    }

    @Nested
    inner class ToolConfiguration {

        @Test
        fun `should include MCP tools when available`() = runBlocking {
            // Arrange
            val mcpTool = object : com.arc.reactor.tool.ToolCallback {
                override val name = "mcp-tool"
                override val description = "MCP Tool"
                override suspend fun call(arguments: Map<String, Any?>) = "result"
            }
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                mcpToolCallbacks = { listOf(mcpTool) }
            )

            // Act
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // Assert - MCP ToolCallback should be wrapped as ArcToolCallbackAdapter
            // and passed via .toolCallbacks() (not .tools() which expects @Tool annotations)
            coVerify { fixture.requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
        }

        @Test
        fun `should respect maxToolsPerRequest limit`() = runBlocking {
            // Arrange
            val manyTools = (1..30).map { i ->
                object : com.arc.reactor.tool.ToolCallback {
                    override val name = "tool-$i"
                    override val description = "Tool $i"
                    override suspend fun call(arguments: Map<String, Any?>) = "result"
                }
            }
            val limitedProperties = properties.copy(maxToolsPerRequest = 5)

            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = limitedProperties,
                mcpToolCallbacks = { manyTools }
            )

            // Act
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // Assert - toolCallbacks should be called with limited tools (ToolCallback-based)
            coVerify { fixture.requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
        }

        @Test
        fun `should reject invalid context window config`() {
            // maxContextWindowTokens < maxOutputTokens should throw
            assertThrows(IllegalArgumentException::class.java) {
                SpringAiAgentExecutor(
                    chatClient = fixture.chatClient,
                    properties = properties.copy(
                        llm = LlmProperties(
                            maxContextWindowTokens = 1000,
                            maxOutputTokens = 2000
                        )
                    )
                )
            }
        }
    }
}
