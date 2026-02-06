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
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

class SpringAiAgentExecutorTest {

    private lateinit var chatClient: ChatClient
    private lateinit var requestSpec: ChatClientRequestSpec
    private lateinit var responseSpec: CallResponseSpec
    private lateinit var properties: AgentProperties

    @BeforeEach
    fun setup() {
        chatClient = mockk()
        requestSpec = mockk(relaxed = true)
        responseSpec = mockk()
        properties = AgentProperties(
            llm = LlmProperties(),
            guard = GuardProperties(),
            rag = RagProperties(),
            concurrency = ConcurrencyProperties()
        )

        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.messages(any<List<org.springframework.ai.chat.messages.Message>>()) } returns requestSpec
        every { requestSpec.tools(*anyVararg<Any>()) } returns requestSpec
        every { requestSpec.options(any<org.springframework.ai.chat.prompt.ChatOptions>()) } returns requestSpec
        every { requestSpec.call() } returns responseSpec
    }

    @Test
    fun `should execute simple command successfully`() = runBlocking {
        // Arrange
        every { responseSpec.content() } returns "Hello! How can I help you?"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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
        assertTrue(result.success)
        assertEquals("Hello! How can I help you?", result.content)
        assertTrue(result.durationMs >= 0)
    }

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
            chatClient = chatClient,
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
        assertFalse(result.success)
        assertEquals("Rate limit exceeded", result.errorMessage)
    }

    @Test
    fun `should run guard with anonymous userId when userId is null`() = runBlocking {
        // Arrange
        val guard = mockk<RequestGuard>()
        coEvery { guard.guard(any()) } returns com.arc.reactor.guard.model.GuardResult.Allowed.DEFAULT
        every { responseSpec.content() } returns "Response"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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
        assertTrue(result.success)
        coVerify(exactly = 1) { guard.guard(match { it.userId == "anonymous" }) }
    }

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

        every { responseSpec.content() } returns "Response"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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
            chatClient = chatClient,
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
        assertFalse(result.success)
        assertEquals("Not allowed", result.errorMessage)
    }

    @Test
    fun `should save conversation to memory`() = runBlocking {
        // Arrange
        val memoryStore = InMemoryMemoryStore()
        every { responseSpec.content() } returns "Hello there!"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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
        assertNotNull(memory)
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

        every { responseSpec.content() } returns "New response"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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
        coVerify { requestSpec.messages(any<List<org.springframework.ai.chat.messages.Message>>()) }
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

        every { responseSpec.content() } returns "Response"
        every { responseSpec.chatResponse() } returns chatResponse

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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
        assertTrue(result.success)
        val tokenUsage = requireNotNull(result.tokenUsage)
        assertEquals(100, tokenUsage.promptTokens)
        assertEquals(50, tokenUsage.completionTokens)
        assertEquals(150, tokenUsage.totalTokens)
    }

    @Test
    fun `should handle LLM exception gracefully`() = runBlocking {
        // Arrange
        every { requestSpec.call() } throws RuntimeException("LLM service unavailable")

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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
        assertFalse(result.success)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `should translate rate limit error`() = runBlocking {
        // Arrange
        every { requestSpec.call() } throws RuntimeException("Rate limit exceeded")

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("Rate limit exceeded"))
    }

    @Test
    fun `should translate timeout error`() = runBlocking {
        // Arrange
        every { requestSpec.call() } throws RuntimeException("Connection timeout")

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("Request timed out"))
    }

    @Test
    fun `should include MCP tools when available`() = runBlocking {
        // Arrange
        val mcpTool = object : com.arc.reactor.tool.ToolCallback {
            override val name = "mcp-tool"
            override val description = "MCP Tool"
            override suspend fun call(arguments: Map<String, Any?>) = "result"
        }
        every { responseSpec.content() } returns "Response"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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

        // Assert - MCP ToolCallback should be wrapped and passed as Spring AI tool
        coVerify { requestSpec.tools(*anyVararg<Any>()) }
    }

    @Test
    fun `should use custom error message resolver`() = runBlocking {
        // Arrange
        every { requestSpec.call() } throws RuntimeException("Rate limit exceeded")

        val koreanResolver = ErrorMessageResolver { code, _ ->
            when (code) {
                AgentErrorCode.RATE_LIMITED -> "요청 한도를 초과했습니다."
                AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다."
                else -> code.defaultMessage
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
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
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("요청 한도"))
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

        every { responseSpec.content() } returns "Successful response"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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

    @Test
    fun `should save memory with non-String sessionId type`() = runBlocking {
        // Arrange
        val memoryStore = InMemoryMemoryStore()
        every { responseSpec.content() } returns "Response"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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

    @Test
    fun `should reject invalid context window config`() {
        // maxContextWindowTokens < maxOutputTokens should throw
        assertThrows(IllegalArgumentException::class.java) {
            SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties.copy(
                    llm = LlmProperties(
                        maxContextWindowTokens = 1000,
                        maxOutputTokens = 2000
                    )
                )
            )
        }
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

        every { responseSpec.content() } returns "Response"
        every { responseSpec.chatResponse() } returns null

        every { requestSpec.tools(*anyVararg<Any>()) } returns requestSpec

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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

        // Assert - tools method should have been called with limited tools
        coVerify { requestSpec.tools(*anyVararg<Any>()) }
    }
}
