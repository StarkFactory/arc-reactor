package com.arc.reactor.agent

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.metrics.AgentMetrics
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import reactor.core.publisher.Flux

/**
 * P0 Tests for timeout edge cases.
 *
 * ## Important: Tool-level timeout limitation
 * [ArcToolCallbackAdapter.call] uses `runBlocking(Dispatchers.IO)` which creates
 * an independent coroutine scope. Parent `withTimeout` cancellation does NOT propagate
 * into this scope. Therefore, tool execution delays cannot be cancelled by request timeout.
 *
 * These tests use LLM-level blocking (inside `runInterruptible`, properly cancellable)
 * to verify timeout behavior. Tool-level timeout is a known limitation documented here.
 *
 * @see ConcurrencyTimeoutTest for semaphore and basic timeout tests
 */
class TimeoutEdgeCaseTest {

    private lateinit var fixture: AgentTestFixture

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class TimeoutDuringReActLoop {

        @Test
        fun `should timeout when second LLM call in ReAct loop is slow`() = runBlocking {
            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 20,
                    requestTimeoutMs = 300
                )
            )

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            // First call returns quickly (tool call), second call hangs (simulates slow LLM)
            val toolCallSpec = fixture.mockToolCallResponse(listOf(toolCall))

            every { fixture.requestSpec.call() } returnsMany listOf(
                toolCallSpec,
                // Second LLM call blocks (inside runInterruptible → properly cancellable)
                mockk<org.springframework.ai.chat.client.ChatClient.CallResponseSpec>().also {
                    every { it.chatResponse() } answers {
                        Thread.sleep(5000) // Blocks inside runInterruptible, interruptible
                        AgentTestFixture.simpleChatResponse("Never reached")
                    }
                }
            )

            val tool = AgentTestFixture.toolCallback("my_tool", result = "tool result")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use my_tool")
            )

            assertFalse(result.success,
                "Should fail due to timeout during second LLM call in ReAct loop")
            assertEquals(AgentErrorCode.TIMEOUT, result.errorCode,
                "Error code should be TIMEOUT")
        }

        @Test
        fun `should record timeout in metrics during ReAct loop`() = runBlocking {
            val metrics = mockk<AgentMetrics>(relaxed = true)
            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 20,
                    requestTimeoutMs = 200
                )
            )

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall)),
                mockk<org.springframework.ai.chat.client.ChatClient.CallResponseSpec>().also {
                    every { it.chatResponse() } answers {
                        Thread.sleep(5000)
                        AgentTestFixture.simpleChatResponse("Never")
                    }
                }
            )

            val tool = AgentTestFixture.toolCallback("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                agentMetrics = metrics
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Go"))

            verify(exactly = 1) {
                metrics.recordExecution(match {
                    !it.success && it.errorCode == AgentErrorCode.TIMEOUT
                })
            }
        }
    }

    @Nested
    inner class TimeoutDuringStreaming {

        @Test
        fun `should emit timeout error when streaming LLM hangs after tool call`() = runBlocking {
            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 20,
                    requestTimeoutMs = 300
                )
            )

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            // First stream returns tool call quickly, second stream hangs
            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(AgentTestFixture.toolCallChunk(listOf(toolCall), "Processing...")),
                Flux.just(AgentTestFixture.textChunk("start"))
                    .concatWith(Flux.never()) // Hangs after emitting "start"
            )

            val tool = AgentTestFixture.toolCallback("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            val fullText = chunks.joinToString("")
            assertTrue(
                fullText.contains("error", ignoreCase = true) ||
                    fullText.contains("timeout", ignoreCase = true),
                "Should contain timeout error in stream output, got: $fullText"
            )
        }

        @Test
        fun `should emit timeout error when initial stream hangs`() = runBlocking {
            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 20,
                    requestTimeoutMs = 200
                )
            )

            // Stream hangs from the start
            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(AgentTestFixture.textChunk("partial"))
                    .concatWith(Flux.never())

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            val fullText = chunks.joinToString("")
            assertTrue(fullText.contains("partial"),
                "Should have emitted partial content before timeout")
            assertTrue(
                fullText.contains("error", ignoreCase = true) ||
                    fullText.contains("timeout", ignoreCase = true),
                "Should contain timeout error after partial content, got: $fullText"
            )
        }
    }

    @Nested
    inner class TimeoutErrorPropagation {

        @Test
        fun `timeout error should have correct error code and message`() = runBlocking {
            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 20,
                    requestTimeoutMs = 100
                )
            )

            // LLM call hangs
            every { fixture.requestSpec.call() } answers {
                Thread.sleep(5000)
                fixture.callResponseSpec
            }

            fixture.mockCallResponse("Never reached")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertFalse(result.success, "Should fail on timeout")
            assertEquals(AgentErrorCode.TIMEOUT, result.errorCode,
                "Error code should be TIMEOUT")
            assertNotNull(result.errorMessage,
                "Error message should not be null")
            assertTrue(result.durationMs > 0,
                "Duration should be recorded even on timeout")
        }

        @Test
        fun `timeout should run afterAgentComplete hook with failure`() = runBlocking {
            val hookExecutor = mockk<com.arc.reactor.hook.HookExecutor>(relaxed = true)
            io.mockk.coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns
                com.arc.reactor.hook.model.HookResult.Continue

            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 20,
                    requestTimeoutMs = 100
                )
            )

            every { fixture.requestSpec.call() } answers {
                Thread.sleep(5000)
                fixture.callResponseSpec
            }

            fixture.mockCallResponse("Never")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            io.mockk.coVerify(exactly = 1) {
                hookExecutor.executeAfterAgentComplete(
                    any(),
                    match { !it.success }
                )
            }
        }
    }
}
