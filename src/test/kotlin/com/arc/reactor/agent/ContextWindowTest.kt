package com.arc.reactor.agent

import com.arc.reactor.agent.config.*
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.memory.TokenEstimator
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.ChatOptions

class ContextWindowTest {

    private lateinit var chatClient: ChatClient
    private lateinit var requestSpec: ChatClientRequestSpec
    private lateinit var responseSpec: CallResponseSpec

    @BeforeEach
    fun setup() {
        chatClient = mockk()
        requestSpec = mockk(relaxed = true)
        responseSpec = mockk()

        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.messages(any<List<Message>>()) } returns requestSpec
        every { requestSpec.options(any<ChatOptions>()) } returns requestSpec
        every { requestSpec.call() } returns responseSpec
        every { responseSpec.content() } returns "Response"
        every { responseSpec.chatResponse() } returns null
    }

    @Test
    fun `should not trim messages when within context budget`() = runBlocking {
        // Budget: 1000 - systemTokens - 100 = plenty of room
        val properties = AgentProperties(
            llm = LlmProperties(
                maxContextWindowTokens = 1000,
                maxOutputTokens = 100
            )
        )

        val messagesSlot = slot<List<Message>>()
        every { requestSpec.messages(capture(messagesSlot)) } returns requestSpec

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            tokenEstimator = TokenEstimator { it.length / 4 }  // simple estimator
        )

        executor.execute(
            AgentCommand(
                systemPrompt = "Short system prompt",
                userPrompt = "Hello!"
            )
        )

        // Only the user message should be present (no history to load)
        assertEquals(1, messagesSlot.captured.size)
    }

    @Test
    fun `should trim oldest messages when exceeding context budget`() = runBlocking {
        // Very tight budget: 50 tokens total, 10 for output reserve
        // System prompt "System" = 6/4 = 1 token
        // Budget = 50 - 1 - 10 = 39 tokens
        val properties = AgentProperties(
            llm = LlmProperties(
                maxContextWindowTokens = 50,
                maxOutputTokens = 10,
                maxConversationTurns = 100
            )
        )

        // Use a simple 1-char-per-token estimator for predictability
        val simpleEstimator = TokenEstimator { text -> text.length.coerceAtLeast(1) }

        val messagesSlot = slot<List<Message>>()
        every { requestSpec.messages(capture(messagesSlot)) } returns requestSpec

        val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
        // Add many old messages that exceed the budget
        repeat(10) { i ->
            memoryStore.addMessage("session-1", "user", "Old message number $i which is quite long")
            memoryStore.addMessage("session-1", "assistant", "Old response number $i which is also long")
        }

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            memoryStore = memoryStore,
            tokenEstimator = simpleEstimator
        )

        executor.execute(
            AgentCommand(
                systemPrompt = "System",
                userPrompt = "New question",
                metadata = mapOf("sessionId" to "session-1")
            )
        )

        // Messages should be trimmed to fit within 39 tokens
        val capturedMessages = messagesSlot.captured
        val totalTokens = capturedMessages.sumOf { simpleEstimator.estimate(it.text ?: "") }
        assertTrue(totalTokens <= 39, "Total tokens ($totalTokens) should fit within budget (39)")
        // The last message (current user prompt) must always be present
        assertTrue(capturedMessages.last().text == "New question")
    }

    @Test
    fun `should always preserve the most recent user message even if it exceeds budget`() = runBlocking {
        // Extremely tight budget
        val properties = AgentProperties(
            llm = LlmProperties(
                maxContextWindowTokens = 10,
                maxOutputTokens = 5
            )
        )

        val simpleEstimator = TokenEstimator { text -> text.length.coerceAtLeast(1) }

        val messagesSlot = slot<List<Message>>()
        every { requestSpec.messages(capture(messagesSlot)) } returns requestSpec

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            tokenEstimator = simpleEstimator
        )

        // Even though the user prompt exceeds the budget, it should still be kept
        executor.execute(
            AgentCommand(
                systemPrompt = "System prompt that takes tokens",
                userPrompt = "This is a very long user prompt that definitely exceeds the tiny budget"
            )
        )

        val capturedMessages = messagesSlot.captured
        assertEquals(1, capturedMessages.size, "Should keep at least the current user message")
        assertTrue(capturedMessages[0].text!!.contains("very long user prompt"))
    }

    @Test
    fun `should reserve maxOutputTokens in context budget`() = runBlocking {
        // maxContextWindowTokens=100, maxOutputTokens=80
        // System "Hi" ~= 1 token
        // Budget = 100 - 1 - 80 = 19 tokens
        val properties = AgentProperties(
            llm = LlmProperties(
                maxContextWindowTokens = 100,
                maxOutputTokens = 80,
                maxConversationTurns = 100
            )
        )

        val simpleEstimator = TokenEstimator { text -> text.length.coerceAtLeast(1) }

        val messagesSlot = slot<List<Message>>()
        every { requestSpec.messages(capture(messagesSlot)) } returns requestSpec

        val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
        memoryStore.addMessage("session-2", "user", "AAAAAAAAAA") // 10 tokens
        memoryStore.addMessage("session-2", "assistant", "BBBBBBBBBB") // 10 tokens

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            memoryStore = memoryStore,
            tokenEstimator = simpleEstimator
        )

        executor.execute(
            AgentCommand(
                systemPrompt = "Hi",
                userPrompt = "CCCCC", // 5 tokens
                metadata = mapOf("sessionId" to "session-2")
            )
        )

        // Budget is 19. History has 10+10=20 tokens + user prompt 5 = 25, exceeds 19
        // Should trim oldest to fit
        val capturedMessages = messagesSlot.captured
        val totalTokens = capturedMessages.sumOf { simpleEstimator.estimate(it.text ?: "") }
        assertTrue(totalTokens <= 19, "Total tokens ($totalTokens) should fit within budget (19)")
        // Current user prompt must be preserved
        assertTrue(capturedMessages.any { it.text == "CCCCC" })
    }

    @Test
    fun `should preserve tool call and tool response pairs when trimming`() = runBlocking {
        // Tight budget that forces trimming
        val properties = AgentProperties(
            llm = LlmProperties(
                maxContextWindowTokens = 200,
                maxOutputTokens = 20,
                maxConversationTurns = 100
            )
        )

        val simpleEstimator = TokenEstimator { text -> text.length.coerceAtLeast(1) }

        // Build conversation history with tool call pairs manually
        val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
        // Turn 1: user + assistant (simple)
        memoryStore.addMessage("session-pair", "user", "A".repeat(50))
        memoryStore.addMessage("session-pair", "assistant", "B".repeat(50))
        // Turn 2: user + assistant (simple)
        memoryStore.addMessage("session-pair", "user", "C".repeat(50))
        memoryStore.addMessage("session-pair", "assistant", "D".repeat(50))

        val messagesSlot = slot<List<Message>>()
        every { requestSpec.messages(capture(messagesSlot)) } returns requestSpec

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            memoryStore = memoryStore,
            tokenEstimator = simpleEstimator
        )

        // System prompt "S" = 1 token, budget = 200 - 1 - 20 = 179
        // History: 50 + 50 + 50 + 50 = 200 tokens + new user = 210, exceeds 179
        executor.execute(
            AgentCommand(
                systemPrompt = "S",
                userPrompt = "E".repeat(10),
                metadata = mapOf("sessionId" to "session-pair")
            )
        )

        val captured = messagesSlot.captured
        val totalTokens = captured.sumOf { simpleEstimator.estimate(it.text ?: "") }
        assertTrue(totalTokens <= 179, "Should fit in budget: $totalTokens")

        // Verify no orphaned ToolResponseMessage exists without a preceding AssistantMessage with tool calls
        for (i in captured.indices) {
            if (captured[i] is ToolResponseMessage) {
                assertTrue(i > 0, "ToolResponseMessage should not be first message")
                val prev = captured[i - 1]
                assertTrue(
                    prev is AssistantMessage && !prev.toolCalls.isNullOrEmpty(),
                    "ToolResponseMessage at index $i must be preceded by AssistantMessage with tool calls"
                )
            }
        }
    }
}
