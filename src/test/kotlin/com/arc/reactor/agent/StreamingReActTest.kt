package com.arc.reactor.agent

import com.arc.reactor.agent.AgentTestFixture.Companion.textChunk
import com.arc.reactor.agent.AgentTestFixture.Companion.toolCallChunk
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.coVerify
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
 * TDD Tests for Full Streaming ReAct Loop.
 *
 * Tests that executeStream() implements the complete ReAct pattern:
 * Stream → Detect Tool Calls → Execute Tools (with hooks) → Re-Stream → Final Answer
 *
 * Verification focus:
 * - Tool actually called (call count, arguments)
 * - ReAct loop iterations (chatClient.prompt() count)
 * - Hook invocation order and effects
 * - maxToolCalls exact enforcement
 * - Multi-round tool calls
 * - Text chunk ordering (first stream before second stream)
 */
class StreamingReActTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    // =========================================================================
    // 1. ReAct Loop Basic Behavior Verification
    // =========================================================================
    @Nested
    inner class ReActLoopBasic {

        @Test
        fun `도구 호출 감지 후 실행하고 다시 스트리밍해야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "weather", """{"city":"Seoul"}""")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall), "확인할게요")),
                Flux.just(textChunk("서울은 맑고 25도입니다."))
            )

            val tool = TrackingTool("weather", "맑음, 25도")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "서울 날씨?")
            ).toList()

            // Verify 1: Tool was called exactly once
            assertEquals(1, tool.callCount, "Tool should be called exactly once")

            // Verify 2: ReAct loop ran 2 times (tool call -> final response)
            verify(exactly = 2) { fixture.requestSpec.stream() }

            // Verify 3: Both first stream text and second stream text are included
            val fullText = chunks.joinToString("")
            assertTrue(fullText.contains("확인할게요"), "First stream text should be present")
            assertTrue(fullText.contains("서울은 맑고 25도입니다."), "Second stream (final response) should be present")
        }

        @Test
        fun `도구에 올바른 인자가 전달되어야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall(
                "call-1", "function", "weather",
                """{"city":"Seoul","unit":"celsius"}"""
            )

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("결과입니다."))
            )

            val tool = TrackingTool("weather", "25도")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "날씨?")
            ).toList()

            // Verify: Check arguments passed to the tool
            assertEquals(1, tool.capturedArgs.size, "Arguments should be captured exactly once")
            assertEquals("Seoul", tool.capturedArgs[0]["city"], "city=Seoul should be passed")
            assertEquals("celsius", tool.capturedArgs[0]["unit"], "unit=celsius should be passed")
        }

        @Test
        fun `텍스트 청크가 올바른 순서로 전달되어야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "calc", """{}""")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(
                    textChunk("A"),
                    textChunk("B"),
                    toolCallChunk(listOf(toolCall), "C")
                ),
                Flux.just(
                    textChunk("D"),
                    textChunk("E")
                )
            )

            val tool = TrackingTool("calc", "42")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "계산해줘")
            ).toList()

            // Verify: Order must be correct (first stream A,B,C -> tool markers -> second stream D,E)
            val textChunks = chunks.filter { !StreamEventMarker.isMarker(it) }
            assertEquals(listOf("A", "B", "C", "D", "E"), textChunks, "Text chunk order must be preserved")

            // Verify: Tool markers are emitted between iterations
            val markerChunks = chunks.filter { StreamEventMarker.isMarker(it) }
            assertTrue(markerChunks.isNotEmpty()) { "Tool markers should be emitted" }
            assertEquals("tool_start", StreamEventMarker.parse(markerChunks[0])?.first) {
                "First marker should be tool_start"
            }
        }

        @Test
        fun `동시에 여러 도구가 호출되면 모두 실행되어야 한다`() = runBlocking {
            val toolCall1 = AssistantMessage.ToolCall("call-1", "function", "weather", """{"city":"Seoul"}""")
            val toolCall2 = AssistantMessage.ToolCall("call-2", "function", "time", """{"tz":"KST"}""")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall1, toolCall2))),
                Flux.just(textChunk("서울: 맑음. 시간: 3pm."))
            )

            val weatherTool = TrackingTool("weather", "맑음")
            val timeTool = TrackingTool("time", "3pm KST")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(weatherTool, timeTool)
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "날씨랑 시간?")
            ).toList()

            // Verify: Both tools called exactly once each
            assertEquals(1, weatherTool.callCount, "weather tool should be called once")
            assertEquals(1, timeTool.callCount, "time tool should be called once")
        }

        @Test
        fun `도구가 없으면 ReAct 루프 없이 바로 스트리밍해야 한다`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Hello"), textChunk(" World"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            // Verify 1: Text is passed through as-is
            assertEquals(listOf("Hello", " World"), chunks)

            // Verify 2: Streaming happens only once (no ReAct loop)
            verify(exactly = 1) { fixture.requestSpec.stream() }
        }

        @Test
        fun `STANDARD 모드에서는 도구 호출 안 해야 한다`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("직접 응답"))

            val tool = TrackingTool("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            executor.executeStream(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "Hello",
                    mode = AgentMode.STANDARD
                )
            ).toList()

            // Verify: Tool was not called
            assertEquals(0, tool.callCount, "Tool should not be called in STANDARD mode")

            // Verify: Streaming happens only once
            verify(exactly = 1) { fixture.requestSpec.stream() }
        }
    }

    // =========================================================================
    // 2. Multi-Round ReAct
    // =========================================================================
    @Nested
    inner class MultiRoundReAct {

        @Test
        fun `도구 호출이 2라운드 연속 발생해도 정상 처리해야 한다`() = runBlocking {
            val toolCall1 = AssistantMessage.ToolCall("call-1", "function", "search", """{"q":"서울 날씨"}""")
            val toolCall2 = AssistantMessage.ToolCall("call-2", "function", "search", """{"q":"서울 맛집"}""")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                // Round 1: First search
                Flux.just(toolCallChunk(listOf(toolCall1), "검색 중...")),
                // Round 2: Second search
                Flux.just(toolCallChunk(listOf(toolCall2), "추가 검색...")),
                // Round 3: Final response
                Flux.just(textChunk("서울은 맑고, 강남 맛집은 OOO입니다."))
            )

            val tool = TrackingTool("search", "검색 결과")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "서울 날씨랑 맛집 알려줘")
            ).toList()

            // Verify 1: Tool was called exactly 2 times
            assertEquals(2, tool.callCount, "search tool should be called exactly 2 times")

            // Verify 2: ReAct loop ran 3 times
            verify(exactly = 3) { fixture.requestSpec.stream() }

            // Verify 3: Text from all rounds is included
            val fullText = chunks.joinToString("")
            assertTrue(fullText.contains("검색 중..."), "Round 1 text")
            assertTrue(fullText.contains("추가 검색..."), "Round 2 text")
            assertTrue(fullText.contains("서울은 맑고"), "Final response text")

            // Verify 4: Correct arguments passed in each round
            assertEquals("서울 날씨", tool.capturedArgs[0]["q"], "Round 1 argument")
            assertEquals("서울 맛집", tool.capturedArgs[1]["q"], "Round 2 argument")
        }
    }

    // =========================================================================
    // 3. Hook Verification (Enhanced)
    // =========================================================================
    @Nested
    inner class StreamingToolHooks {

        @Test
        fun `BeforeToolCallHook이 도구 이름과 함께 호출되어야 한다`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", """{"key":"value"}""")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("Done"))
            )

            val tool = TrackingTool("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                hookExecutor = hookExecutor
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            // Verify 1: BeforeToolCallHook called with exact tool name
            coVerify(exactly = 1) {
                hookExecutor.executeBeforeToolCall(match {
                    it.toolName == "my_tool" && it.callIndex == 0
                })
            }

            // Verify 2: Tool is actually called after hook passes
            assertEquals(1, tool.callCount, "Tool should be called after hook passes")
        }

        @Test
        fun `AfterToolCallHook이 도구 결과와 함께 호출되어야 한다`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("Done"))
            )

            val tool = TrackingTool("my_tool", "my output 123")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                hookExecutor = hookExecutor
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            // Verify: AfterToolCallHook called with exact result
            coVerify(exactly = 1) {
                hookExecutor.executeAfterToolCall(
                    match { it.toolName == "my_tool" },
                    match {
                        it.output == "my output 123" &&
                            it.success &&
                            it.durationMs >= 0
                    }
                )
            }
        }

        @Test
        fun `BeforeToolCallHook이 거부하면 도구가 호출되지 않아야 한다`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Reject("위험한 도구")

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "dangerous_tool", "{}")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("도구가 차단됨"))
            )

            val tool = TrackingTool("dangerous_tool", "이게 호출되면 안 됨")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                hookExecutor = hookExecutor
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            // Key verification: Tool was NOT called!
            assertEquals(0, tool.callCount, "Rejected tool should never be called")

            // Verify 2: AfterToolCallHook was also not called
            coVerify(exactly = 0) { hookExecutor.executeAfterToolCall(any(), any()) }
        }
    }

    // =========================================================================
    // 4. maxToolCalls Exact Verification
    // =========================================================================
    @Nested
    inner class StreamingMaxToolCalls {

        @Test
        fun `maxToolCalls=2일 때 도구가 정확히 2번만 호출되어야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),  // Round 1
                Flux.just(toolCallChunk(listOf(toolCall))),  // Round 2
                Flux.just(toolCallChunk(listOf(toolCall))),  // Round 3 (limit reached)
                Flux.just(textChunk("최종 응답"))              // Round 4
            )

            val tool = TrackingTool("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "반복", maxToolCalls = 2)
            ).toList()

            // Key verification: Called exactly 2 times only
            assertEquals(2, tool.callCount, "Tool should be called exactly 2 times since maxToolCalls=2")
        }

        @Test
        fun `maxToolCalls=1일 때 도구가 정확히 1번만 호출되어야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),  // Round 1
                Flux.just(toolCallChunk(listOf(toolCall))),  // Round 2 (limit reached)
                Flux.just(textChunk("끝"))
            )

            val tool = TrackingTool("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "반복", maxToolCalls = 1)
            ).toList()

            assertEquals(1, tool.callCount, "Tool should be called exactly 1 time since maxToolCalls=1")
        }
    }

    // =========================================================================
    // 5. Timeout
    // =========================================================================
    @Nested
    inner class StreamingTimeout {

        @Test
        fun `타임아웃 초과 시 에러 메시지가 포함되어야 한다`() = runBlocking {
            val props = properties.copy(
                concurrency = ConcurrencyProperties(requestTimeoutMs = 100)
            )

            every { fixture.streamResponseSpec.chatResponse() } returns Flux.just(textChunk("시작"))
                .concatWith(Flux.never())

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            val fullText = chunks.joinToString("")
            assertTrue(
                fullText.contains("error", ignoreCase = true) ||
                    fullText.contains("timeout", ignoreCase = true),
                "Should contain timeout error, actual: $fullText"
            )
        }
    }

    // =========================================================================
    // 6. AfterAgentComplete Hook + Metrics
    // =========================================================================
    @Nested
    inner class StreamingAfterComplete {

        @Test
        fun `스트리밍 종료 후 AfterAgentComplete이 전체 텍스트와 함께 호출되어야 한다`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue

            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Hello"), textChunk(" World"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            // Verify: success=true, full text included
            coVerify(exactly = 1) {
                hookExecutor.executeAfterAgentComplete(
                    any(),
                    match {
                        it.success &&
                            it.response == "Hello World"  // Exact full text
                    }
                )
            }
        }

        @Test
        fun `도구 사용 후 AfterAgentComplete에 도구 목록이 포함되어야 한다`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("Done"))
            )

            val tool = TrackingTool("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                hookExecutor = hookExecutor
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            // Verify: toolsUsed contains "my_tool"
            coVerify(exactly = 1) {
                hookExecutor.executeAfterAgentComplete(
                    any(),
                    match {
                        it.success &&
                            it.toolsUsed == listOf("my_tool")  // Exactly this tool only
                    }
                )
            }
        }

        @Test
        fun `스트리밍 후 실행 Metrics가 기록되어야 한다`() = runBlocking {
            val metrics = mockk<AgentMetrics>(relaxed = true)

            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Response"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                agentMetrics = metrics
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            // Verify: success=true, content included, durationMs >= 0
            verify(exactly = 1) {
                metrics.recordExecution(match {
                    it.success &&
                        it.content == "Response" &&
                        it.durationMs >= 0
                })
            }
        }

        @Test
        fun `도구 호출 시 Metrics에 도구 이름과 성공 여부가 기록되어야 한다`() = runBlocking {
            val metrics = mockk<AgentMetrics>(relaxed = true)

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("Done"))
            )

            val tool = TrackingTool("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                agentMetrics = metrics
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            // Verify: tool name, durationMs >= 0, success=true
            verify(exactly = 1) {
                metrics.recordToolCall(
                    eq("my_tool"),
                    match { it >= 0 },
                    eq(true)
                )
            }
        }
    }

    // =========================================================================
    // 7. Conversation History Storage
    // =========================================================================
    @Nested
    inner class StreamingMemory {

        @Test
        fun `스트리밍 완료 후 user와 assistant 메시지가 정확히 저장되어야 한다`() = runBlocking {
            val memoryStore = mockk<MemoryStore>(relaxed = true)

            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Hello"), textChunk(" World"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore
            )

            executor.executeStream(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "안녕",
                    metadata = mapOf("sessionId" to "session-123")
                )
            ).toList()

            // Verify: Saved with exact values
            verify(exactly = 1) { memoryStore.addMessage("session-123", "user", "안녕") }
            verify(exactly = 1) { memoryStore.addMessage("session-123", "assistant", "Hello World") }
        }

        @Test
        fun `도구 사용 후 전체 수집된 텍스트가 저장되어야 한다`() = runBlocking {
            val memoryStore = mockk<MemoryStore>(relaxed = true)

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall), "확인 중...")),
                Flux.just(textChunk("결과: 완료"))
            )

            val tool = TrackingTool("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                memoryStore = memoryStore
            )

            executor.executeStream(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "해줘",
                    metadata = mapOf("sessionId" to "session-456")
                )
            ).toList()

            // Verify: Only final round text is saved (intermediate tool-related text excluded)
            verify(exactly = 1) { memoryStore.addMessage("session-456", "user", "해줘") }
            verify(exactly = 1) { memoryStore.addMessage("session-456", "assistant", "결과: 완료") }
        }

        @Test
        fun `sessionId가 없으면 히스토리 저장하지 않아야 한다`() = runBlocking {
            val memoryStore = mockk<MemoryStore>(relaxed = true)

            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Response"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
                // No sessionId in metadata
            ).toList()

            // Verify: addMessage was not called
            verify(exactly = 0) { memoryStore.addMessage(any(), any(), any()) }
        }
    }

    // =========================================================================
    // 8. Tool Error Handling
    // =========================================================================
    @Nested
    inner class StreamingToolErrors {

        @Test
        fun `도구 실행 실패 시 에러 메시지를 LLM에 전달하고 계속 스트리밍해야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "failing_tool", "{}")
            val metrics = mockk<AgentMetrics>(relaxed = true)

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("도구가 실패했습니다."))
            )

            val failingTool = object : ToolCallback {
                override val name = "failing_tool"
                override val description = "실패하는 도구"
                override suspend fun call(arguments: Map<String, Any?>): Any {
                    throw RuntimeException("Connection refused")
                }
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(failingTool),
                agentMetrics = metrics
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            // Verify 1: Stream completed normally (no crash)
            assertTrue(chunks.isNotEmpty(), "Stream should return results")

            // Verify 2: ReAct loop continued (error forwarded to LLM, then response received)
            verify(exactly = 2) { fixture.requestSpec.stream() }

            // Verify 3: Recorded as failure in Metrics
            verify(exactly = 1) { metrics.recordToolCall("failing_tool", any(), false) }
        }

        @Test
        fun `존재하지 않는 도구 호출 시에도 정상 스트리밍해야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "nonexistent", "{}")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("도구를 못 찾았습니다."))
            )

            val tool = TrackingTool("other_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use unknown tool")
            ).toList()

            // Verify 1: other_tool was not called
            assertEquals(0, tool.callCount, "Other tool should not be called")

            // Verify 2: Stream completed normally
            assertTrue(chunks.isNotEmpty(), "Stream should produce at least one chunk")
            verify(exactly = 2) { fixture.requestSpec.stream() }
        }
    }
}
