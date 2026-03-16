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
 * P0 타임아웃 엣지 케이스에 대한 테스트.
 *
 * ## 범위
 * 수동 ReAct 흐름에서 도구 실행은 [com.arc.reactor.agent.impl.ToolCallOrchestrator]가 처리하며,
 * 이미 도구별 타임아웃을 적용합니다.
 * [com.arc.reactor.agent.impl.ArcToolCallbackAdapter.call]도 블로킹 콜백 경로에 대해 타임아웃을 적용합니다.
 *
 * 이 테스트는 LLM 수준 블로킹(`runInterruptible` 내부, 적절히 취소 가능)에 초점을 맞추어
 * ReAct 루프에서의 요청 타임아웃 동작을 검증합니다.
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
        fun `second LLM call in ReAct loop is slow일 때 timeout해야 한다`() = runBlocking {
            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 20,
                    requestTimeoutMs = 300
                )
            )

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            // 첫 번째 호출은 빠르게 반환 (도구 호출), 두 번째 호출은 중단됨 (느린 LLM 시뮬레이션)
            val toolCallSpec = fixture.mockToolCallResponse(listOf(toolCall))

            every { fixture.requestSpec.call() } returnsMany listOf(
                toolCallSpec,
                // 두 번째 LLM 호출이 블록됨 (runInterruptible 내부 → 적절히 취소 가능)
                mockk<org.springframework.ai.chat.client.ChatClient.CallResponseSpec>().also {
                    every { it.chatResponse() } answers {
                        Thread.sleep(1_000) // Blocks inside runInterruptible, interruptible
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
        fun `record timeout in metrics during ReAct loop해야 한다`() = runBlocking {
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
                        Thread.sleep(1_000)
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
        fun `streaming LLM hangs after tool call일 때 emit timeout error해야 한다`() = runBlocking {
            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 20,
                    requestTimeoutMs = 300
                )
            )

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            // 첫 번째 스트림은 도구 호출을 빠르게 반환, 두 번째 스트림은 중단됨
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
        fun `initial stream hangs일 때 emit timeout error해야 한다`() = runBlocking {
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
        fun `timeout error은(는) have correct error code and message해야 한다`() = runBlocking {
            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 20,
                    requestTimeoutMs = 100
                )
            )

            // LLM call hangs
            every { fixture.requestSpec.call() } answers {
                Thread.sleep(1_000)
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
        fun `timeout은(는) run afterAgentComplete hook with failure해야 한다`() = runBlocking {
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
                Thread.sleep(1_000)
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
