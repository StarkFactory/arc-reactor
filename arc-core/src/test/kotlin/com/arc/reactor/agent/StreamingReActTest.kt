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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import reactor.core.publisher.Flux

/**
 * 전체 스트리밍 ReAct 루프에 대한 TDD 테스트.
 *
 * executeStream()이 완전한 ReAct 패턴을 구현하는지 테스트합니다:
 * 스트림 → 도구 호출 감지 → 도구 실행 (훅 포함) → 재스트림 → 최종 응답
 *
 * 검증 초점:
 * - 도구 실제 호출 여부 (호출 횟수, 인수)
 * - ReAct 루프 반복 횟수 (chatClient.prompt() 호출 수)
 * - 훅 호출 순서 및 효과
 * - maxToolCalls 정확한 적용
 * - 다중 라운드 도구 호출
 * - 텍스트 청크 순서 (첫 번째 스트림이 두 번째 스트림보다 먼저)
 */
class StreamingReActTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    // =========================================================================
    // 1. ReAct 루프 기본 동작 검증
    // =========================================================================
    @Nested
    inner class ReActLoopBasic {

        @Test
        fun `도구 호출 감지 후 실행하고 다시 스트리밍해야 한다`() = runTest {
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

            // 검증 1: 도구가 정확히 한 번 호출되었습니다
            assertEquals(1, tool.callCount, "Tool should be called exactly once")

            // 검증 2: ReAct 루프가 2번 실행되었습니다 (도구 호출 -> 최종 응답)
            verify(exactly = 2) { fixture.requestSpec.stream() }

            // 검증 3: 첫 번째 스트림 텍스트와 두 번째 스트림 텍스트가 모두 포함되었습니다
            val fullText = chunks.joinToString("")
            assertTrue(fullText.contains("확인할게요"), "First stream text should be present")
            assertTrue(fullText.contains("서울은 맑고 25도입니다."), "Second stream (final response) should be present")
        }

        @Test
        fun `도구에 올바른 인자가 전달되어야 한다`() = runTest {
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

            // 검증: 도구에 전달된 인수를 확인합니다
            assertEquals(1, tool.capturedArgs.size, "Arguments should be captured exactly once")
            assertEquals("Seoul", tool.capturedArgs[0]["city"], "city=Seoul should be passed")
            assertEquals("celsius", tool.capturedArgs[0]["unit"], "unit=celsius should be passed")
        }

        @Test
        fun `텍스트 청크가 올바른 순서로 전달되어야 한다`() = runTest {
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

            // 검증: 순서가 올바라야 합니다 (첫 번째 스트림 A,B,C -> 도구 마커 -> 두 번째 스트림 D,E)
            val textChunks = chunks.filter { !StreamEventMarker.isMarker(it) }
            assertEquals(listOf("A", "B", "C", "D", "E"), textChunks, "Text chunk order must be preserved")

            // 검증: 반복 간에 도구 마커가 방출됩니다
            val markerChunks = chunks.filter { StreamEventMarker.isMarker(it) }
            assertTrue(markerChunks.isNotEmpty()) { "Tool markers should be emitted" }
            assertEquals("tool_start", StreamEventMarker.parse(markerChunks[0])?.first) {
                "First marker should be tool_start"
            }
        }

        @Test
        fun `동시에 여러 도구가 호출되면 모두 실행되어야 한다`() = runTest {
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

            // 검증: 두 도구 모두 정확히 한 번씩 호출되었습니다
            assertEquals(1, weatherTool.callCount, "weather tool should be called once")
            assertEquals(1, timeTool.callCount, "time tool should be called once")
        }

        @Test
        fun `도구가 없으면 ReAct 루프 없이 바로 스트리밍해야 한다`() = runTest {
            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Hello"), textChunk(" World"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            // 검증 1: 텍스트가 그대로 전달됩니다
            assertEquals(listOf("Hello", " World"), chunks)

            // 검증 2: 스트리밍이 한 번만 발생합니다 (ReAct 루프 없음)
            verify(exactly = 1) { fixture.requestSpec.stream() }
        }

        @Test
        fun `STANDARD 모드에서는 도구 호출 안 해야 한다`() = runTest {
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

            // 검증: 도구가 호출되지 않았습니다
            assertEquals(0, tool.callCount, "Tool should not be called in STANDARD mode")

            // 검증: 스트리밍이 한 번만 발생합니다
            verify(exactly = 1) { fixture.requestSpec.stream() }
        }
    }

    // =========================================================================
    // 2. 다중 라운드 ReAct
    // =========================================================================
    @Nested
    inner class MultiRoundReAct {

        @Test
        fun `도구 호출이 2라운드 연속 발생해도 정상 처리해야 한다`() = runTest {
            val toolCall1 = AssistantMessage.ToolCall("call-1", "function", "search", """{"q":"서울 날씨"}""")
            val toolCall2 = AssistantMessage.ToolCall("call-2", "function", "search", """{"q":"서울 맛집"}""")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                // 라운드 1: 첫 번째 검색
                Flux.just(toolCallChunk(listOf(toolCall1), "검색 중...")),
                // 라운드 2: 두 번째 검색
                Flux.just(toolCallChunk(listOf(toolCall2), "추가 검색...")),
                // 라운드 3: 최종 응답
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

            // 검증 1: 도구가 정확히 2번 호출되었습니다
            assertEquals(2, tool.callCount, "search tool should be called exactly 2 times")

            // 검증 2: ReAct 루프가 3번 실행되었습니다
            verify(exactly = 3) { fixture.requestSpec.stream() }

            // 검증 3: 모든 라운드의 텍스트가 포함되었습니다
            val fullText = chunks.joinToString("")
            assertTrue(fullText.contains("검색 중..."), "Round 1 text")
            assertTrue(fullText.contains("추가 검색..."), "Round 2 text")
            assertTrue(fullText.contains("서울은 맑고"), "Final response text")

            // 검증 4: 각 라운드에 올바른 인수가 전달되었습니다
            assertEquals("서울 날씨", tool.capturedArgs[0]["q"], "Round 1 argument")
            assertEquals("서울 맛집", tool.capturedArgs[1]["q"], "Round 2 argument")
        }
    }

    // =========================================================================
    // 3. 훅 검증 (향상됨)
    // =========================================================================
    @Nested
    inner class StreamingToolHooks {

        @Test
        fun `BeforeToolCallHook이 도구 이름과 함께 호출되어야 한다`() = runTest {
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

            // 검증 1: BeforeToolCallHook이 정확한 도구 이름으로 호출되었습니다
            coVerify(exactly = 1) {
                hookExecutor.executeBeforeToolCall(match {
                    it.toolName == "my_tool" && it.callIndex == 0
                })
            }

            // 검증 2: 훅이 통과한 후 도구가 실제로 호출되었습니다
            assertEquals(1, tool.callCount, "Tool should be called after hook passes")
        }

        @Test
        fun `AfterToolCallHook이 도구 결과와 함께 호출되어야 한다`() = runTest {
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

            // 검증: AfterToolCallHook이 정확한 결과와 함께 호출되었습니다
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
        fun `BeforeToolCallHook이 거부하면 도구가 호출되지 않아야 한다`() = runTest {
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

            // 핵심 검증: 도구가 호출되지 않았습니다!
            assertEquals(0, tool.callCount, "Rejected tool should never be called")

            // 검증 2: AfterToolCallHook도 호출되지 않았습니다
            coVerify(exactly = 0) { hookExecutor.executeAfterToolCall(any(), any()) }
        }
    }

    // =========================================================================
    // 4. maxToolCalls 정확한 검증
    // =========================================================================
    @Nested
    inner class StreamingMaxToolCalls {

        @Test
        fun `maxToolCalls=2일 때 도구가 정확히 2번만 호출되어야 한다`() = runTest {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),  // 라운드 1
                Flux.just(toolCallChunk(listOf(toolCall))),  // 라운드 2
                Flux.just(toolCallChunk(listOf(toolCall))),  // 라운드 3 (제한 도달)
                Flux.just(textChunk("최종 응답"))  // 라운드 4
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

            // 핵심 검증: 정확히 2번만 호출되었습니다
            assertEquals(2, tool.callCount, "Tool should be called exactly 2 times since maxToolCalls=2")
        }

        @Test
        fun `maxToolCalls=1일 때 도구가 정확히 1번만 호출되어야 한다`() = runTest {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),  // 라운드 1
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
    // 5. 타임아웃
    // =========================================================================
    @Nested
    inner class StreamingTimeout {

        @Test
        fun `타임아웃 초과 시 에러 메시지가 포함되어야 한다`() = runTest {
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
    // 6. AfterAgentComplete 훅 + 메트릭
    // =========================================================================
    @Nested
    inner class StreamingAfterComplete {

        @Test
        fun `스트리밍 종료 후 AfterAgentComplete이 전체 텍스트와 함께 호출되어야 한다`() = runTest {
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

            // 검증: success=true, 전체 텍스트 포함
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
        fun `도구 사용 후 AfterAgentComplete에 도구 목록이 포함되어야 한다`() = runTest {
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

            // 검증: toolsUsed에 "my_tool"이 포함됩니다
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
        fun `스트리밍 후 실행 Metrics가 기록되어야 한다`() = runTest {
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

            // 검증: success=true, 콘텐츠 포함, durationMs >= 0
            verify(exactly = 1) {
                metrics.recordStreamingExecution(match {
                    it.success &&
                        it.content == "Response" &&
                        it.durationMs >= 0
                })
            }
        }

        @Test
        fun `도구 호출 시 Metrics에 도구 이름과 성공 여부가 기록되어야 한다`() = runTest {
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

            // 검증: 도구 이름, durationMs >= 0, success=true
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
    // 7. 대화 기록 저장
    // =========================================================================
    @Nested
    inner class StreamingMemory {

        @Test
        fun `스트리밍 완료 후 user와 assistant 메시지가 정확히 저장되어야 한다`() = runTest {
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

            // 검증: 정확한 값으로 저장 (userId가 설정되지 않으면 "anonymous"로 기본값)
            verify(exactly = 1) { memoryStore.addMessage("session-123", "user", "안녕", "anonymous") }
            verify(exactly = 1) { memoryStore.addMessage("session-123", "assistant", "Hello World", "anonymous") }
        }

        @Test
        fun `도구 사용 후 전체 수집된 텍스트가 저장되어야 한다`() = runTest {
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

            // 검증: 최종 라운드 텍스트만 저장됩니다 (중간 도구 관련 텍스트 제외)
            verify(exactly = 1) { memoryStore.addMessage("session-456", "user", "해줘", "anonymous") }
            verify(exactly = 1) { memoryStore.addMessage("session-456", "assistant", "결과: 완료", "anonymous") }
        }

        @Test
        fun `sessionId가 없으면 히스토리 저장하지 않아야 한다`() = runTest {
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
                // 메타데이터에 sessionId 없음
            ).toList()

            // 검증: addMessage가 호출되지 않았습니다
            verify(exactly = 0) { memoryStore.addMessage(any(), any(), any(), any()) }
        }
    }

    // =========================================================================
    // 8. 도구 오류 처리
    // =========================================================================
    @Nested
    inner class StreamingToolErrors {

        @Test
        fun `도구 실행 실패 시 에러 메시지를 LLM에 전달하고 계속 스트리밍해야 한다`() = runTest {
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

            // 검증 1: 스트림이 정상 완료되었습니다 (크래시 없음)
            assertTrue(chunks.isNotEmpty(), "Stream should return results")

            // 검증 2: ReAct 루프가 계속되었습니다 (오류가 LLM에 전달된 후 응답 수신)
            verify(exactly = 2) { fixture.requestSpec.stream() }

            // 검증 3: Metrics에 실패로 기록되었습니다
            verify(exactly = 1) { metrics.recordToolCall("failing_tool", any(), false) }
        }

        @Test
        fun `존재하지 않는 도구 호출 시에도 정상 스트리밍해야 한다`() = runTest {
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

            // 검증 1: other_tool이 호출되지 않았습니다
            assertEquals(0, tool.callCount, "Other tool should not be called")

            // 검증 2: 스트림이 정상 완료되었습니다
            assertTrue(chunks.isNotEmpty(), "Stream should produce at least one chunk")
            verify(exactly = 2) { fixture.requestSpec.stream() }
        }
    }
}
