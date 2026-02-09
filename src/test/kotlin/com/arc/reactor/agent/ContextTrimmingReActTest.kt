package com.arc.reactor.agent

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.memory.TokenEstimator
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

/**
 * P0 Tests for context trimming during multi-round ReAct loops.
 *
 * Verifies that when the context window fills up during tool call iterations,
 * the trimming logic:
 * - Never orphans ToolResponseMessages (always removed as pair with preceding AssistantMessage)
 * - Always preserves the most recent UserMessage (current prompt)
 * - Correctly handles the growing message list as tool results accumulate
 */
class ContextTrimmingReActTest {

    private lateinit var fixture: AgentTestFixture
    private val charEstimator = TokenEstimator { text -> text.length.coerceAtLeast(1) }

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class TrimmingDuringReActLoop {

        @Test
        fun `should trim old history while preserving tool call pairs during multi-round ReAct`() = runBlocking {
            // Tight context budget forces trimming during ReAct iterations
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 300,
                    maxOutputTokens = 50,
                    maxConversationTurns = 100
                )
            )

            // System prompt "S" = 1 char/token → budget = 300 - 1 - 50 = 249
            val toolCall1 = AssistantMessage.ToolCall("call-1", "function", "search", """{"q":"round 1"}""")
            val toolCall2 = AssistantMessage.ToolCall("call-2", "function", "search", """{"q":"round 2"}""")
            val toolCall3 = AssistantMessage.ToolCall("call-3", "function", "search", """{"q":"round 3"}""")

            // Track all message lists sent to LLM
            val allMessageCaptures = mutableListOf<List<Message>>()
            every { fixture.requestSpec.messages(any<List<Message>>()) } answers {
                val msgs = firstArg<List<Message>>()
                allMessageCaptures.add(msgs.toList()) // Snapshot copy
                fixture.requestSpec
            }

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall1)),
                fixture.mockToolCallResponse(listOf(toolCall2)),
                fixture.mockToolCallResponse(listOf(toolCall3)),
                fixture.mockFinalResponse("Final answer after 3 rounds")
            )

            // Tool that returns large output (eats context budget)
            val tool = AgentTestFixture.toolCallback("search", result = "X".repeat(80))

            val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
            // Pre-fill some conversation history
            memoryStore.addMessage("session-trim", "user", "A".repeat(60))
            memoryStore.addMessage("session-trim", "assistant", "B".repeat(60))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                memoryStore = memoryStore,
                tokenEstimator = charEstimator
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "S",
                    userPrompt = "Find information",
                    metadata = mapOf("sessionId" to "session-trim"),
                    maxToolCalls = 3
                )
            )

            result.assertSuccess()

            // Verify message integrity in EVERY iteration
            for ((iteration, messages) in allMessageCaptures.withIndex()) {
                for (i in messages.indices) {
                    if (messages[i] is ToolResponseMessage) {
                        assertTrue(i > 0,
                            "Iteration $iteration: ToolResponseMessage at index $i should not be first")
                        val prev = messages[i - 1]
                        assertTrue(
                            prev is AssistantMessage && !prev.toolCalls.isNullOrEmpty(),
                            "Iteration $iteration: ToolResponseMessage at index $i must follow " +
                                "AssistantMessage with tool calls, but found: ${prev::class.simpleName}"
                        )
                    }
                }

                // Last message should always be UserMessage (current prompt)
                // or messages added by tool call loop (AssistantMessage/ToolResponseMessage)
                // But the original UserMessage must still be present somewhere
                val hasUserMessage = messages.any { it is UserMessage }
                assertTrue(hasUserMessage,
                    "Iteration $iteration: UserMessage should always be present in messages")
            }
        }
    }

    @Nested
    inner class ExtremeBudgetPressure {

        @Test
        fun `should keep only current user message when budget is extremely tight`() = runBlocking {
            // Budget so tight only the user message fits
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 50,
                    maxOutputTokens = 20,
                    maxConversationTurns = 100
                )
            )

            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("Brief response")

            val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
            memoryStore.addMessage("session-tight", "user", "A".repeat(100))
            memoryStore.addMessage("session-tight", "assistant", "B".repeat(100))
            memoryStore.addMessage("session-tight", "user", "C".repeat(100))
            memoryStore.addMessage("session-tight", "assistant", "D".repeat(100))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore,
                tokenEstimator = charEstimator
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "System prompt that uses tokens",
                    userPrompt = "Current question",
                    metadata = mapOf("sessionId" to "session-tight")
                )
            )

            result.assertSuccess()

            val captured = messagesSlot.captured
            // Current user prompt must always survive
            assertTrue(captured.any { it is UserMessage && it.text == "Current question" },
                "Current user prompt must always be preserved, got: ${captured.map { "${it::class.simpleName}(${it.text?.take(20)})" }}")
        }

        @Test
        fun `should handle zero budget gracefully without crash`() = runBlocking {
            // maxOutputTokens >= maxContextWindowTokens → budget goes negative
            // (init block requires maxContextWindowTokens > maxOutputTokens, so use equal values that pass)
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 100,
                    maxOutputTokens = 99
                )
            )

            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("OK")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                tokenEstimator = TokenEstimator { text -> text.length * 10 } // Inflated estimator
            )

            // Should not throw, even if budget is effectively zero after system prompt
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "A".repeat(50), // 500 "tokens" with inflated estimator
                    userPrompt = "Hello"
                )
            )

            result.assertSuccess()
            assertTrue(messagesSlot.captured.isNotEmpty(),
                "Should still have at least the user message")
        }
    }

    @Nested
    inner class MessagePairValidation {

        @Test
        fun `should never leave orphaned ToolResponseMessage after trimming history`() = runBlocking {
            // Moderate budget with pre-loaded tool call history
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 200,
                    maxOutputTokens = 30,
                    maxConversationTurns = 100
                )
            )

            val allMessageCaptures = mutableListOf<List<Message>>()
            every { fixture.requestSpec.messages(any<List<Message>>()) } answers {
                allMessageCaptures.add(firstArg<List<Message>>().toList())
                fixture.requestSpec
            }

            // Tool call that produces medium-sized output
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "lookup", """{"id":"123"}""")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockFinalResponse("Result after two lookups")
            )

            val tool = AgentTestFixture.toolCallback("lookup", result = "Y".repeat(60))

            // Pre-fill with mixed history
            val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
            memoryStore.addMessage("session-pairs", "user", "Old question 1".repeat(5))
            memoryStore.addMessage("session-pairs", "assistant", "Old answer 1".repeat(5))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                memoryStore = memoryStore,
                tokenEstimator = charEstimator
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "S",
                    userPrompt = "Look up item 123 twice",
                    metadata = mapOf("sessionId" to "session-pairs"),
                    maxToolCalls = 5
                )
            )

            result.assertSuccess()

            // Validate ALL message snapshots for pair integrity
            for ((iteration, messages) in allMessageCaptures.withIndex()) {
                var i = 0
                while (i < messages.size) {
                    val msg = messages[i]
                    if (msg is ToolResponseMessage && i == 0) {
                        fail<Unit>("Iteration $iteration: ToolResponseMessage should not be first message")
                    }
                    if (msg is ToolResponseMessage) {
                        val prev = messages[i - 1]
                        assertTrue(
                            prev is AssistantMessage && !prev.toolCalls.isNullOrEmpty(),
                            "Iteration $iteration index $i: Orphaned ToolResponseMessage found. " +
                                "Preceding message is ${prev::class.simpleName}"
                        )
                    }
                    i++
                }
            }
        }
    }
}
