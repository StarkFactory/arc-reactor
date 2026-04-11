package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.budget.StepBudgetTracker
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.TokenEstimator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger

/**
 * StreamingReActLoopExecutor에 대한 테스트.
 *
 * 스트리밍 ReAct 루프 실행기의 동작을 검증합니다.
 */
class StreamingReActLoopExecutorTest {

    @Test
    fun `llm returns no tool calls일 때 stream final text해야 한다`() = runTest {
        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
        every { requestSpec.stream() } returns streamResponseSpec
        every { streamResponseSpec.chatResponse() } returns Flux.just(AgentTestFixture.textChunk("hello"))

        val optionsUsed = mutableListOf<Boolean>()
        val emitted = mutableListOf<String>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi")
        val loopExecutor = StreamingReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 10_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = mockk(relaxed = true),
            buildRequestSpec = { _, _, _, _, _ -> requestSpec },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, hasTools ->
                optionsUsed.add(hasTools)
                ChatOptions.builder().build()
            },
            agentMetrics = metrics
        )

        val result = loopExecutor.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", metadata = mapOf("channel" to "web")),
            activeChatClient = mockk(relaxed = true),
            systemPrompt = "sys",
            initialTools = emptyList(),
            conversationHistory = emptyList(),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            allowedTools = null,
            maxToolCalls = 3,
            emit = { emitted.add(it) }
        )

        assertTrue(result.success, "Single-turn streaming execution should succeed")
        assertEquals("hello", result.collectedContent)
        assertEquals("hello", result.lastIterationContent)
        assertEquals(listOf("hello"), emitted)
        assertEquals(listOf(false), optionsUsed)
        val stageTimings = readStageTimings(hookContext)
        assertTrue(stageTimings.containsKey("llm_calls"), "Streaming loop should record llm_calls timing")
        assertTrue(stageTimings.containsKey("tool_execution"), "Streaming loop should record tool_execution timing")
        verify { metrics.recordStageLatency("llm_calls", any(), match { it["channel"] == "web" }) }
        verify { metrics.recordStageLatency("tool_execution", any(), match { it["channel"] == "web" }) }
    }

    @Test
    fun `maxToolCalls reached일 때 disable tools해야 한다`() = runTest {
        val toolCall = AssistantMessage.ToolCall("tc-1", "call", "search", "{}")
        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
        every { requestSpec.stream() } returns streamResponseSpec
        every { streamResponseSpec.chatResponse() } returnsMany listOf(
            Flux.just(AgentTestFixture.toolCallChunk(listOf(toolCall), "thinking")),
            Flux.just(AgentTestFixture.textChunk("done"))
        )

        val toolOrchestrator = mockk<ToolCallOrchestrator>()
        coEvery {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            (it.invocation.args[4] as AtomicInteger).set(1)
            listOf(ToolResponseMessage.ToolResponse("tc-1", "search", "ok"))
        }

        val optionsUsed = mutableListOf<Boolean>()
        val emitted = mutableListOf<String>()
        val loopExecutor = StreamingReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 10_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = toolOrchestrator,
            buildRequestSpec = { _, _, _, _, _ -> requestSpec },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, hasTools ->
                optionsUsed.add(hasTools)
                ChatOptions.builder().build()
            }
        )

        val result = loopExecutor.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", maxToolCalls = 1),
            activeChatClient = mockk(relaxed = true),
            systemPrompt = "sys",
            initialTools = listOf(mockk<Any>(relaxed = true)),
            conversationHistory = listOf(MediaConverter.buildUserMessage("history", emptyList())),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            allowedTools = null,
            maxToolCalls = 1,
            emit = { emitted.add(it) }
        )

        assertTrue(result.success, "ReAct loop should succeed after tool call and final response")
        assertEquals("thinkingdone", result.collectedContent)
        assertEquals("done", result.lastIterationContent)
        assertTrue(optionsUsed.contains(true), "First iteration should use tools (tools enabled)")
        assertTrue(optionsUsed.contains(false), "Final iteration after maxToolCalls should disable tools")
        assertTrue(emitted.contains(StreamEventMarker.toolStart("search")), "toolStart marker for 'search' should be emitted")
        assertTrue(emitted.contains(StreamEventMarker.toolEnd("search")), "toolEnd marker for 'search' should be emitted")
        coVerify(exactly = 1) {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `maxToolCalls is zero일 때 start with tools disabled해야 한다`() = runTest {
        val toolCall = AssistantMessage.ToolCall("tc-1", "call", "search", "{}")
        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
        every { requestSpec.stream() } returns streamResponseSpec
        every { streamResponseSpec.chatResponse() } returns Flux.just(
            AgentTestFixture.toolCallChunk(listOf(toolCall), "tool-free")
        )

        val toolOrchestrator = mockk<ToolCallOrchestrator>()
        val optionsUsed = mutableListOf<Boolean>()
        val emitted = mutableListOf<String>()
        val loopExecutor = StreamingReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 10_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = toolOrchestrator,
            buildRequestSpec = { _, _, _, _, _ -> requestSpec },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, hasTools ->
                optionsUsed.add(hasTools)
                ChatOptions.builder().build()
            }
        )

        val result = loopExecutor.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", maxToolCalls = 0),
            activeChatClient = mockk(relaxed = true),
            systemPrompt = "sys",
            initialTools = listOf(mockk<Any>(relaxed = true)),
            conversationHistory = emptyList(),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            allowedTools = null,
            maxToolCalls = 0,
            emit = { emitted.add(it) }
        )

        assertTrue(result.success, "Streaming loop should finish without executing tools")
        assertEquals("tool-free", result.collectedContent)
        assertEquals("tool-free", result.lastIterationContent)
        assertEquals(listOf("tool-free"), emitted)
        assertEquals(listOf(false), optionsUsed)
        coVerify(exactly = 0) {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Nested
    inner class 예산추적 {

        /** 토큰 사용량 메타데이터를 포함한 ChatResponse 청크를 생성한다. */
        private fun chunkWithTokens(text: String, promptTokens: Int, completionTokens: Int): ChatResponse {
            val usage = mockk<Usage>()
            every { usage.promptTokens } returns promptTokens
            every { usage.completionTokens } returns completionTokens
            every { usage.totalTokens } returns promptTokens + completionTokens

            val metadata = mockk<ChatResponseMetadata>()
            every { metadata.usage } returns usage
            every { metadata.model } returns null

            val msg = AssistantMessage(text)
            val generation = mockk<Generation>()
            every { generation.output } returns msg

            val response = mockk<ChatResponse>()
            every { response.result } returns generation
            every { response.results } returns listOf(generation)
            every { response.metadata } returns metadata
            return response
        }

        @Test
        fun `budgetTracker EXHAUSTED 시 루프를 실패로 종료하고 에러 이벤트를 emit해야 한다`() = runTest {
            val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
            every { requestSpec.stream() } returns streamResponseSpec
            // 110토큰 응답 → maxTokens=50인 예산 초과
            every { streamResponseSpec.chatResponse() } returns Flux.just(
                chunkWithTokens("partial answer", promptTokens = 30, completionTokens = 80)
            )

            val emitted = mutableListOf<String>()
            val loopExecutor = StreamingReActLoopExecutor(
                messageTrimmer = ConversationMessageTrimmer(
                    maxContextWindowTokens = 10_000,
                    outputReserveTokens = 100,
                    tokenEstimator = TokenEstimator { it.length }
                ),
                toolCallOrchestrator = mockk(relaxed = true),
                buildRequestSpec = { _, _, _, _, _ -> requestSpec },
                callWithRetry = { block -> block() },
                buildChatOptions = { _, _ -> ChatOptions.builder().build() }
            )

            // maxTokens=50, 응답은 110토큰 → EXHAUSTED
            val budgetTracker = StepBudgetTracker(maxTokens = 50)

            val result = loopExecutor.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                activeChatClient = mockk(relaxed = true),
                systemPrompt = "sys",
                initialTools = emptyList(),
                conversationHistory = emptyList(),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                allowedTools = null,
                maxToolCalls = 3,
                emit = { emitted.add(it) },
                budgetTracker = budgetTracker
            )

            assertFalse(result.success) {
                "토큰 예산 초과 시 루프는 실패로 종료되어야 한다"
            }
            val errorMarkers = emitted
                .mapNotNull { StreamEventMarker.parse(it) }
                .filter { it.first == "error" }
            assertTrue(errorMarkers.isNotEmpty()) {
                "토큰 예산 초과 시 error 이벤트가 emit되어야 한다, 실제 emit: $emitted"
            }
            assertTrue(
                errorMarkers.any { it.second.contains("예산") || it.second.contains("토큰") }
            ) { "에러 이벤트에 예산 초과 메시지가 포함되어야 한다, markers: $errorMarkers" }
        }

        @Test
        fun `budgetTracker null일 때 예산 검사 없이 정상 실행되어야 한다`() = runTest {
            val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
            every { requestSpec.stream() } returns streamResponseSpec
            every { streamResponseSpec.chatResponse() } returns Flux.just(
                AgentTestFixture.textChunk("ok")
            )

            val loopExecutor = StreamingReActLoopExecutor(
                messageTrimmer = ConversationMessageTrimmer(
                    maxContextWindowTokens = 10_000,
                    outputReserveTokens = 100,
                    tokenEstimator = TokenEstimator { it.length }
                ),
                toolCallOrchestrator = mockk(relaxed = true),
                buildRequestSpec = { _, _, _, _, _ -> requestSpec },
                callWithRetry = { block -> block() },
                buildChatOptions = { _, _ -> ChatOptions.builder().build() }
            )

            val result = loopExecutor.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                activeChatClient = mockk(relaxed = true),
                systemPrompt = "sys",
                initialTools = emptyList(),
                conversationHistory = emptyList(),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                allowedTools = null,
                maxToolCalls = 3,
                emit = {},
                budgetTracker = null
            )

            assertTrue(result.success) {
                "budgetTracker가 null이면 예산 검사 없이 정상 완료되어야 한다"
            }
            assertEquals("ok", result.collectedContent) {
                "콘텐츠가 올바르게 수집되어야 한다"
            }
        }
    }

    @Nested
    inner class 토큰사용량기록 {

        @Test
        fun `recordTokenUsage 콜백이 토큰 메타데이터로 호출되어야 한다`() = runTest {
            val usage = mockk<Usage>()
            every { usage.promptTokens } returns 200
            every { usage.completionTokens } returns 100
            every { usage.totalTokens } returns 300

            val metadata = mockk<ChatResponseMetadata>()
            every { metadata.usage } returns usage
            every { metadata.model } returns "gemini-pro"

            val assistantMsg = AssistantMessage("result")
            val generation = mockk<Generation>()
            every { generation.output } returns assistantMsg

            val chunkResponse = mockk<ChatResponse>()
            every { chunkResponse.result } returns generation
            every { chunkResponse.results } returns listOf(generation)
            every { chunkResponse.metadata } returns metadata

            val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
            every { requestSpec.stream() } returns streamResponseSpec
            every { streamResponseSpec.chatResponse() } returns Flux.just(chunkResponse)

            val recordedUsages = mutableListOf<com.arc.reactor.agent.model.TokenUsage>()
            val loopExecutor = StreamingReActLoopExecutor(
                messageTrimmer = ConversationMessageTrimmer(
                    maxContextWindowTokens = 10_000,
                    outputReserveTokens = 100,
                    tokenEstimator = TokenEstimator { it.length }
                ),
                toolCallOrchestrator = mockk(relaxed = true),
                buildRequestSpec = { _, _, _, _, _ -> requestSpec },
                callWithRetry = { block -> block() },
                buildChatOptions = { _, _ -> ChatOptions.builder().build() },
                recordTokenUsage = { tokenUsage, _ -> recordedUsages.add(tokenUsage) }
            )

            loopExecutor.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                activeChatClient = mockk(relaxed = true),
                systemPrompt = "sys",
                initialTools = emptyList(),
                conversationHistory = emptyList(),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                allowedTools = null,
                maxToolCalls = 3,
                emit = {}
            )

            assertTrue(recordedUsages.isNotEmpty()) {
                "토큰 메타데이터가 있는 응답에 대해 recordTokenUsage가 호출되어야 한다"
            }
            val recorded = recordedUsages.first()
            assertEquals(200, recorded.promptTokens) {
                "프롬프트 토큰 수가 올바르게 기록되어야 한다"
            }
            assertEquals(100, recorded.completionTokens) {
                "완성 토큰 수가 올바르게 기록되어야 한다"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // R272: 멀티 청크 toolCall 누적 (collectStreamChunks 덮어쓰기 버그 fix)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * R272 regression: 멀티 청크 스트리밍에서 도구 호출이 여러 청크에 걸쳐 도착할 때
     * 모든 도구 호출이 누적되어야 한다 (이전: 마지막 청크만 보존).
     *
     * 이 시나리오는 OpenAI/일부 프로바이더의 incremental tool call streaming에서 발생할 수 있다.
     * 현 Spring AI Gemini/Anthropic는 단일 청크에 모든 도구 호출을 담아 보내므로 숨어있던
     * P2 버그였다.
     */
    @Nested
    inner class R272MultiChunkToolCallAccumulation {

        @Test
        fun `R272 fix - 여러 청크에 걸친 서로 다른 toolCall은 모두 누적되어야 한다`() = runTest {
            // 청크 1: tool call A
            // 청크 2: tool call B (다른 ID)
            // 청크 3: 텍스트만
            // 누적 결과: [A, B] 모두 executeInParallel에 전달되어야 함
            val toolCallA = AssistantMessage.ToolCall("id-a", "function", "tool_a", "{\"x\":1}")
            val toolCallB = AssistantMessage.ToolCall("id-b", "function", "tool_b", "{\"y\":2}")

            val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
            every { requestSpec.stream() } returns streamResponseSpec
            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(
                    AgentTestFixture.toolCallChunk(listOf(toolCallA), "thinking-1"),
                    AgentTestFixture.toolCallChunk(listOf(toolCallB), "thinking-2"),
                    AgentTestFixture.textChunk("more text")
                ),
                Flux.just(AgentTestFixture.textChunk("done"))
            )

            // executeInParallel에 전달된 toolCalls를 캡처
            val capturedToolCalls = mutableListOf<List<AssistantMessage.ToolCall>>()
            val toolOrchestrator = mockk<ToolCallOrchestrator>()
            coEvery {
                toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                @Suppress("UNCHECKED_CAST")
                val toolCalls = it.invocation.args[0] as List<AssistantMessage.ToolCall>
                capturedToolCalls.add(toolCalls)
                (it.invocation.args[4] as AtomicInteger).addAndGet(toolCalls.size)
                toolCalls.map { tc ->
                    ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "ok-${tc.name()}")
                }
            }

            val loopExecutor = StreamingReActLoopExecutor(
                messageTrimmer = ConversationMessageTrimmer(
                    maxContextWindowTokens = 10_000,
                    outputReserveTokens = 100,
                    tokenEstimator = TokenEstimator { it.length }
                ),
                toolCallOrchestrator = toolOrchestrator,
                buildRequestSpec = { _, _, _, _, _ -> requestSpec },
                callWithRetry = { block -> block() },
                buildChatOptions = { _, _ -> ChatOptions.builder().build() }
            )

            loopExecutor.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", maxToolCalls = 5),
                activeChatClient = mockk(relaxed = true),
                systemPrompt = "sys",
                initialTools = listOf(mockk<Any>(relaxed = true)),
                conversationHistory = emptyList(),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                allowedTools = null,
                maxToolCalls = 5,
                emit = {}
            )

            // R272 fix 핵심: 첫 번째 executeInParallel 호출에 두 도구 호출이 모두 포함되어야 함
            assertTrue(capturedToolCalls.isNotEmpty()) {
                "executeInParallel이 호출되어야 한다"
            }
            val firstCall = capturedToolCalls.first()
            assertEquals(2, firstCall.size) {
                "R272 fix: 두 청크에 걸친 도구 호출이 모두 누적되어야 함. " +
                    "이전 버그에서는 마지막 청크의 1개만 남았음. actual=${firstCall.map { it.id() }}"
            }
            assertTrue(firstCall.any { it.id() == "id-a" }) {
                "첫 번째 청크의 toolCall A가 보존되어야 함"
            }
            assertTrue(firstCall.any { it.id() == "id-b" }) {
                "두 번째 청크의 toolCall B가 보존되어야 함"
            }
        }

        @Test
        fun `R272 fix - 동일 ID가 여러 청크에 등장하면 최신 버전이 우선되어야 한다`() = runTest {
            // 청크 1: tool call ID=x with arguments {x:1}
            // 청크 2: tool call ID=x with arguments {x:2} (같은 ID, 갱신된 arguments)
            // 누적 결과: [x with arguments {x:2}] 1개만, 최신 버전
            val toolCallV1 = AssistantMessage.ToolCall("id-x", "function", "tool_x", "{\"x\":1}")
            val toolCallV2 = AssistantMessage.ToolCall("id-x", "function", "tool_x", "{\"x\":2}")

            val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
            every { requestSpec.stream() } returns streamResponseSpec
            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(
                    AgentTestFixture.toolCallChunk(listOf(toolCallV1)),
                    AgentTestFixture.toolCallChunk(listOf(toolCallV2))
                ),
                Flux.just(AgentTestFixture.textChunk("done"))
            )

            val capturedToolCalls = mutableListOf<List<AssistantMessage.ToolCall>>()
            val toolOrchestrator = mockk<ToolCallOrchestrator>()
            coEvery {
                toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                @Suppress("UNCHECKED_CAST")
                val toolCalls = it.invocation.args[0] as List<AssistantMessage.ToolCall>
                capturedToolCalls.add(toolCalls)
                (it.invocation.args[4] as AtomicInteger).addAndGet(toolCalls.size)
                toolCalls.map { tc ->
                    ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "ok")
                }
            }

            val loopExecutor = StreamingReActLoopExecutor(
                messageTrimmer = ConversationMessageTrimmer(
                    maxContextWindowTokens = 10_000,
                    outputReserveTokens = 100,
                    tokenEstimator = TokenEstimator { it.length }
                ),
                toolCallOrchestrator = toolOrchestrator,
                buildRequestSpec = { _, _, _, _, _ -> requestSpec },
                callWithRetry = { block -> block() },
                buildChatOptions = { _, _ -> ChatOptions.builder().build() }
            )

            loopExecutor.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", maxToolCalls = 5),
                activeChatClient = mockk(relaxed = true),
                systemPrompt = "sys",
                initialTools = listOf(mockk<Any>(relaxed = true)),
                conversationHistory = emptyList(),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                allowedTools = null,
                maxToolCalls = 5,
                emit = {}
            )

            val firstCall = capturedToolCalls.first()
            assertEquals(1, firstCall.size) {
                "동일 ID는 1개로 dedup되어야 함. actual=${firstCall.map { it.id() }}"
            }
            // 최신 버전(arguments={x:2})이 우선
            assertEquals("{\"x\":2}", firstCall.first().arguments()) {
                "동일 ID 재등장 시 최신 버전이 우선"
            }
        }

        @Test
        fun `R272 회귀 - 단일 청크 시나리오는 동일하게 동작해야 한다`() = runTest {
            // R272 변경이 기존 단일 청크 동작에 영향을 주지 않는지 검증
            val toolCall = AssistantMessage.ToolCall("tc-1", "function", "search", "{}")
            val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
            every { requestSpec.stream() } returns streamResponseSpec
            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(AgentTestFixture.toolCallChunk(listOf(toolCall), "thinking")),
                Flux.just(AgentTestFixture.textChunk("done"))
            )

            val capturedToolCalls = mutableListOf<List<AssistantMessage.ToolCall>>()
            val toolOrchestrator = mockk<ToolCallOrchestrator>()
            coEvery {
                toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                @Suppress("UNCHECKED_CAST")
                val toolCalls = it.invocation.args[0] as List<AssistantMessage.ToolCall>
                capturedToolCalls.add(toolCalls)
                (it.invocation.args[4] as AtomicInteger).addAndGet(toolCalls.size)
                listOf(ToolResponseMessage.ToolResponse("tc-1", "search", "ok"))
            }

            val loopExecutor = StreamingReActLoopExecutor(
                messageTrimmer = ConversationMessageTrimmer(
                    maxContextWindowTokens = 10_000,
                    outputReserveTokens = 100,
                    tokenEstimator = TokenEstimator { it.length }
                ),
                toolCallOrchestrator = toolOrchestrator,
                buildRequestSpec = { _, _, _, _, _ -> requestSpec },
                callWithRetry = { block -> block() },
                buildChatOptions = { _, _ -> ChatOptions.builder().build() }
            )

            loopExecutor.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", maxToolCalls = 5),
                activeChatClient = mockk(relaxed = true),
                systemPrompt = "sys",
                initialTools = listOf(mockk<Any>(relaxed = true)),
                conversationHistory = emptyList(),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                allowedTools = null,
                maxToolCalls = 5,
                emit = {}
            )

            assertEquals(1, capturedToolCalls.first().size) {
                "단일 청크 단일 도구 호출은 동일하게 1개"
            }
            assertEquals("tc-1", capturedToolCalls.first().first().id())
        }
    }
}
