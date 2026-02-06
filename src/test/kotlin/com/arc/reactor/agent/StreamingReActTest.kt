package com.arc.reactor.agent

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.config.GuardProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.client.ChatClient.StreamResponseSpec
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
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

    private lateinit var chatClient: ChatClient
    private lateinit var requestSpec: ChatClientRequestSpec
    private lateinit var streamResponseSpec: StreamResponseSpec
    private lateinit var properties: AgentProperties

    @BeforeEach
    fun setup() {
        chatClient = mockk()
        requestSpec = mockk(relaxed = true)
        streamResponseSpec = mockk()
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
        every { requestSpec.options(any<ChatOptions>()) } returns requestSpec
        every { requestSpec.stream() } returns streamResponseSpec
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private fun textChunk(text: String): ChatResponse {
        return ChatResponse(listOf(Generation(AssistantMessage(text))))
    }

    private fun toolCallChunk(
        toolCalls: List<AssistantMessage.ToolCall>,
        text: String = ""
    ): ChatResponse {
        val msg = AssistantMessage.builder()
            .content(text)
            .toolCalls(toolCalls)
            .build()
        return ChatResponse(listOf(Generation(msg)))
    }

    /**
     * Tracking ToolCallback: 호출 횟수, 전달된 인자를 기록
     */
    private fun trackingToolCallback(
        name: String,
        result: String = "tool result"
    ): TrackingTool {
        return TrackingTool(name, result)
    }

    class TrackingTool(
        override val name: String,
        private val result: String
    ) : ToolCallback {
        override val description = "Test tool: $name"
        var callCount = 0
            private set
        val capturedArgs = mutableListOf<Map<String, Any?>>()

        override suspend fun call(arguments: Map<String, Any?>): Any {
            callCount++
            capturedArgs.add(arguments)
            return result
        }
    }

    // =========================================================================
    // 1. ReAct 루프 기본 동작 검증
    // =========================================================================
    @Nested
    inner class ReActLoopBasic {

        @Test
        fun `도구 호출 감지 후 실행하고 다시 스트리밍해야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "weather", """{"city":"Seoul"}""")

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall), "확인할게요")),
                Flux.just(textChunk("서울은 맑고 25도입니다."))
            )

            val tool = trackingToolCallback("weather", "맑음, 25도")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "서울 날씨?")
            ).toList()

            // 검증 1: 도구가 정확히 1번 호출됨
            assertEquals(1, tool.callCount, "도구는 정확히 1번 호출되어야 한다")

            // 검증 2: ReAct 루프가 2번 돌았음 (도구 호출 → 최종 응답)
            verify(exactly = 2) { requestSpec.stream() }

            // 검증 3: 첫 번째 스트림 텍스트 + 두 번째 스트림 텍스트 모두 포함
            val fullText = chunks.joinToString("")
            assertTrue(fullText.contains("확인할게요"), "첫 번째 스트림의 텍스트가 있어야 한다")
            assertTrue(fullText.contains("서울은 맑고 25도입니다."), "두 번째 스트림(최종 응답)이 있어야 한다")
        }

        @Test
        fun `도구에 올바른 인자가 전달되어야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall(
                "call-1", "function", "weather",
                """{"city":"Seoul","unit":"celsius"}"""
            )

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("결과입니다."))
            )

            val tool = trackingToolCallback("weather", "25도")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "날씨?")
            ).toList()

            // 검증: 도구에 전달된 인자 확인
            assertEquals(1, tool.capturedArgs.size, "인자가 1번 캡처되어야 한다")
            assertEquals("Seoul", tool.capturedArgs[0]["city"], "city=Seoul이 전달되어야 한다")
            assertEquals("celsius", tool.capturedArgs[0]["unit"], "unit=celsius가 전달되어야 한다")
        }

        @Test
        fun `텍스트 청크가 올바른 순서로 전달되어야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "calc", """{}""")

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
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

            val tool = trackingToolCallback("calc", "42")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "계산해줘")
            ).toList()

            // 검증: 순서가 정확해야 한다 (첫 스트림 A,B,C → 도구 실행 → 두번째 스트림 D,E)
            assertEquals(listOf("A", "B", "C", "D", "E"), chunks, "텍스트 청크 순서가 보장되어야 한다")
        }

        @Test
        fun `동시에 여러 도구가 호출되면 모두 실행되어야 한다`() = runBlocking {
            val toolCall1 = AssistantMessage.ToolCall("call-1", "function", "weather", """{"city":"Seoul"}""")
            val toolCall2 = AssistantMessage.ToolCall("call-2", "function", "time", """{"tz":"KST"}""")

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall1, toolCall2))),
                Flux.just(textChunk("서울: 맑음. 시간: 3pm."))
            )

            val weatherTool = trackingToolCallback("weather", "맑음")
            val timeTool = trackingToolCallback("time", "3pm KST")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(weatherTool, timeTool)
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "날씨랑 시간?")
            ).toList()

            // 검증: 두 도구 모두 정확히 1번씩 호출
            assertEquals(1, weatherTool.callCount, "weather 도구는 1번 호출되어야 한다")
            assertEquals(1, timeTool.callCount, "time 도구는 1번 호출되어야 한다")
        }

        @Test
        fun `도구가 없으면 ReAct 루프 없이 바로 스트리밍해야 한다`() = runBlocking {
            every { streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Hello"), textChunk(" World"))

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            // 검증 1: 텍스트가 그대로 전달됨
            assertEquals(listOf("Hello", " World"), chunks)

            // 검증 2: 스트리밍은 1번만 (ReAct 루프 안 돎)
            verify(exactly = 1) { requestSpec.stream() }
        }

        @Test
        fun `STANDARD 모드에서는 도구 호출 안 해야 한다`() = runBlocking {
            every { streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("직접 응답"))

            val tool = trackingToolCallback("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
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

            // 검증: 도구가 호출되지 않았음
            assertEquals(0, tool.callCount, "STANDARD 모드에서는 도구가 호출되면 안 된다")

            // 검증: 스트리밍 1번만
            verify(exactly = 1) { requestSpec.stream() }
        }
    }

    // =========================================================================
    // 2. 다중 라운드 ReAct
    // =========================================================================
    @Nested
    inner class MultiRoundReAct {

        @Test
        fun `도구 호출이 2라운드 연속 발생해도 정상 처리해야 한다`() = runBlocking {
            val toolCall1 = AssistantMessage.ToolCall("call-1", "function", "search", """{"q":"서울 날씨"}""")
            val toolCall2 = AssistantMessage.ToolCall("call-2", "function", "search", """{"q":"서울 맛집"}""")

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                // 1라운드: 첫 번째 검색
                Flux.just(toolCallChunk(listOf(toolCall1), "검색 중...")),
                // 2라운드: 두 번째 검색
                Flux.just(toolCallChunk(listOf(toolCall2), "추가 검색...")),
                // 3라운드: 최종 응답
                Flux.just(textChunk("서울은 맑고, 강남 맛집은 OOO입니다."))
            )

            val tool = trackingToolCallback("search", "검색 결과")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "서울 날씨랑 맛집 알려줘")
            ).toList()

            // 검증 1: 도구가 정확히 2번 호출됨
            assertEquals(2, tool.callCount, "search 도구는 정확히 2번 호출되어야 한다")

            // 검증 2: ReAct 루프가 3번 돌았음
            verify(exactly = 3) { requestSpec.stream() }

            // 검증 3: 모든 라운드의 텍스트가 포함됨
            val fullText = chunks.joinToString("")
            assertTrue(fullText.contains("검색 중..."), "1라운드 텍스트")
            assertTrue(fullText.contains("추가 검색..."), "2라운드 텍스트")
            assertTrue(fullText.contains("서울은 맑고"), "최종 응답 텍스트")

            // 검증 4: 각 라운드에서 올바른 인자가 전달됨
            assertEquals("서울 날씨", tool.capturedArgs[0]["q"], "1라운드 인자")
            assertEquals("서울 맛집", tool.capturedArgs[1]["q"], "2라운드 인자")
        }
    }

    // =========================================================================
    // 3. Hook 검증 (강화)
    // =========================================================================
    @Nested
    inner class StreamingToolHooks {

        @Test
        fun `BeforeToolCallHook이 도구 이름과 함께 호출되어야 한다`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", """{"key":"value"}""")

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("Done"))
            )

            val tool = trackingToolCallback("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                hookExecutor = hookExecutor
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            // 검증 1: BeforeToolCallHook이 정확한 도구 이름으로 호출됨
            coVerify(exactly = 1) {
                hookExecutor.executeBeforeToolCall(match {
                    it.toolName == "my_tool" && it.callIndex == 0
                })
            }

            // 검증 2: Hook 통과 후 도구가 실제로 호출됨
            assertEquals(1, tool.callCount, "Hook 통과 후 도구가 호출되어야 한다")
        }

        @Test
        fun `AfterToolCallHook이 도구 결과와 함께 호출되어야 한다`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("Done"))
            )

            val tool = trackingToolCallback("my_tool", "my output 123")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                hookExecutor = hookExecutor
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            // 검증: AfterToolCallHook이 정확한 결과로 호출됨
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

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("도구가 차단됨"))
            )

            val tool = trackingToolCallback("dangerous_tool", "이게 호출되면 안 됨")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                hookExecutor = hookExecutor
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            // 핵심 검증: 도구가 호출되지 않았다!
            assertEquals(0, tool.callCount, "거부된 도구는 절대 호출되면 안 된다")

            // 검증 2: AfterToolCallHook도 호출되지 않았다
            coVerify(exactly = 0) { hookExecutor.executeAfterToolCall(any(), any()) }
        }
    }

    // =========================================================================
    // 4. maxToolCalls 정확한 검증
    // =========================================================================
    @Nested
    inner class StreamingMaxToolCalls {

        @Test
        fun `maxToolCalls=2일 때 도구가 정확히 2번만 호출되어야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),  // 1라운드
                Flux.just(toolCallChunk(listOf(toolCall))),  // 2라운드
                Flux.just(toolCallChunk(listOf(toolCall))),  // 3라운드 (제한 걸림)
                Flux.just(textChunk("최종 응답"))              // 4라운드
            )

            val tool = trackingToolCallback("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "반복", maxToolCalls = 2)
            ).toList()

            // 핵심 검증: 정확히 2번만 호출
            assertEquals(2, tool.callCount, "maxToolCalls=2이므로 도구는 정확히 2번만 호출되어야 한다")
        }

        @Test
        fun `maxToolCalls=1일 때 도구가 정확히 1번만 호출되어야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),  // 1라운드
                Flux.just(toolCallChunk(listOf(toolCall))),  // 2라운드 (제한 걸림)
                Flux.just(textChunk("끝"))
            )

            val tool = trackingToolCallback("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "반복", maxToolCalls = 1)
            ).toList()

            assertEquals(1, tool.callCount, "maxToolCalls=1이므로 도구는 정확히 1번만 호출되어야 한다")
        }
    }

    // =========================================================================
    // 5. 타임아웃
    // =========================================================================
    @Nested
    inner class StreamingTimeout {

        @Test
        fun `타임아웃 초과 시 에러 메시지가 포함되어야 한다`() = runBlocking {
            val props = properties.copy(
                concurrency = ConcurrencyProperties(requestTimeoutMs = 100)
            )

            every { streamResponseSpec.chatResponse() } returns Flux.just(textChunk("시작"))
                .concatWith(Flux.never())

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = props
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            val fullText = chunks.joinToString("")
            assertTrue(
                fullText.contains("error", ignoreCase = true) ||
                    fullText.contains("timeout", ignoreCase = true),
                "타임아웃 에러가 포함되어야 한다, 실제: $fullText"
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

            every { streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Hello"), textChunk(" World"))

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
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
                            it.response == "Hello World"  // 정확히 전체 텍스트
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

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("Done"))
            )

            val tool = trackingToolCallback("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                hookExecutor = hookExecutor
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            // 검증: toolsUsed에 "my_tool" 포함
            coVerify(exactly = 1) {
                hookExecutor.executeAfterAgentComplete(
                    any(),
                    match {
                        it.success &&
                            it.toolsUsed == listOf("my_tool")  // 정확히 이 도구만
                    }
                )
            }
        }

        @Test
        fun `스트리밍 후 실행 Metrics가 기록되어야 한다`() = runBlocking {
            val metrics = mockk<AgentMetrics>(relaxed = true)

            every { streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Response"))

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                agentMetrics = metrics
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            // 검증: success=true, content 포함, durationMs >= 0
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

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("Done"))
            )

            val tool = trackingToolCallback("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
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
    // 7. 대화 히스토리 저장
    // =========================================================================
    @Nested
    inner class StreamingMemory {

        @Test
        fun `스트리밍 완료 후 user와 assistant 메시지가 정확히 저장되어야 한다`() = runBlocking {
            val memoryStore = mockk<MemoryStore>(relaxed = true)

            every { streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Hello"), textChunk(" World"))

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
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

            // 검증: 정확한 값으로 저장
            verify(exactly = 1) { memoryStore.addMessage("session-123", "user", "안녕") }
            verify(exactly = 1) { memoryStore.addMessage("session-123", "assistant", "Hello World") }
        }

        @Test
        fun `도구 사용 후 전체 수집된 텍스트가 저장되어야 한다`() = runBlocking {
            val memoryStore = mockk<MemoryStore>(relaxed = true)

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall), "확인 중...")),
                Flux.just(textChunk("결과: 완료"))
            )

            val tool = trackingToolCallback("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
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

            // 검증: 두 라운드의 텍스트가 합쳐져서 저장됨
            verify(exactly = 1) { memoryStore.addMessage("session-456", "user", "해줘") }
            verify(exactly = 1) { memoryStore.addMessage("session-456", "assistant", "확인 중...결과: 완료") }
        }

        @Test
        fun `sessionId가 없으면 히스토리 저장하지 않아야 한다`() = runBlocking {
            val memoryStore = mockk<MemoryStore>(relaxed = true)

            every { streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Response"))

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                memoryStore = memoryStore
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
                // metadata에 sessionId 없음
            ).toList()

            // 검증: addMessage가 호출되지 않음
            verify(exactly = 0) { memoryStore.addMessage(any(), any(), any()) }
        }
    }

    // =========================================================================
    // 8. 도구 에러 처리
    // =========================================================================
    @Nested
    inner class StreamingToolErrors {

        @Test
        fun `도구 실행 실패 시 에러 메시지를 LLM에 전달하고 계속 스트리밍해야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "failing_tool", "{}")
            val metrics = mockk<AgentMetrics>(relaxed = true)

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
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
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(failingTool),
                agentMetrics = metrics
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            // 검증 1: 스트림이 정상 완료됨 (크래시 안 남)
            assertTrue(chunks.isNotEmpty(), "스트림이 결과를 반환해야 한다")

            // 검증 2: ReAct 루프가 계속 돌았음 (LLM에 에러 전달 후 응답 받음)
            verify(exactly = 2) { requestSpec.stream() }

            // 검증 3: Metrics에 실패로 기록됨
            verify(exactly = 1) { metrics.recordToolCall("failing_tool", any(), false) }
        }

        @Test
        fun `존재하지 않는 도구 호출 시에도 정상 스트리밍해야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "nonexistent", "{}")

            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("도구를 못 찾았습니다."))
            )

            val tool = trackingToolCallback("other_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use unknown tool")
            ).toList()

            // 검증 1: other_tool은 호출되지 않음
            assertEquals(0, tool.callCount, "다른 도구가 호출되면 안 된다")

            // 검증 2: 스트림은 정상 완료
            assertTrue(chunks.isNotEmpty())
            verify(exactly = 2) { requestSpec.stream() }
        }
    }
}
