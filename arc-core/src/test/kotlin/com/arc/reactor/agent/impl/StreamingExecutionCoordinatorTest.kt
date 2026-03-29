package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.memory.ConversationManager
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Semaphore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message

/**
 * StreamingExecutionCoordinator에 대한 테스트.
 *
 * 스트리밍 실행 조정 로직을 검증합니다.
 */
class StreamingExecutionCoordinatorTest {

    // ── 공유 픽스처 헬퍼 ──

    /** 성공 경로용 기본 루프 실행기 mock을 생성한다. */
    private fun successLoopExecutor(
        content: String = "done"
    ): StreamingReActLoopExecutor = mockk<StreamingReActLoopExecutor>().also {
        coEvery {
            it.execute(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns StreamingLoopResult(success = true, collectedContent = content, lastIterationContent = content)
    }

    /** 테스트용 기본 코디네이터를 생성한다. */
    private fun buildCoordinator(
        guard: RequestGuard? = null,
        hookExecutor: HookExecutor? = null,
        loopExecutor: StreamingReActLoopExecutor = successLoopExecutor(),
        conversationManager: ConversationManager = mockk<ConversationManager>().also {
            coEvery { it.loadHistory(any()) } returns emptyList<Message>()
        },
        metrics: AgentMetrics = mockk(relaxed = true),
        requestTimeoutMs: Long = 0L
    ): Pair<StreamingExecutionCoordinator, AgentMetrics> {
        val coordinator = StreamingExecutionCoordinator(
            concurrencySemaphore = Semaphore(1),
            requestTimeoutMs = requestTimeoutMs,
            maxToolCallsLimit = 4,
            preExecutionResolver = PreExecutionResolver(
                guard = guard,
                hookExecutor = hookExecutor,
                intentResolver = null,
                blockedIntents = emptySet(),
                agentMetrics = metrics
            ),
            conversationManager = conversationManager,
            ragContextRetriever = RagContextRetriever(
                enabled = false,
                topK = 4,
                rerankEnabled = false,
                ragPipeline = null,
                retrievalTimeoutMs = 5_000L
            ),
            systemPromptBuilder = SystemPromptBuilder(),
            toolPreparationPlanner = ToolPreparationPlanner(
                localTools = emptyList(),
                toolCallbacks = emptyList(),
                mcpToolCallbacks = { emptyList() },
                toolSelector = null,
                maxToolsPerRequest = 8,
                fallbackToolTimeoutMs = 1_000L
            ),
            resolveChatClient = { mockk<ChatClient>(relaxed = true) },
            resolveIntentAllowedTools = { null },
            streamingReActLoopExecutor = loopExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentErrorPolicy = AgentErrorPolicy(),
            agentMetrics = metrics
        )
        return coordinator to metrics
    }

    @Nested
    inner class 성공경로 {

        @Test
        fun `단계별 타이밍과 채널 태그를 기록해야 한다`() = runTest {
            val (coordinator, metrics) = buildCoordinator()
            val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi", channel = "web")

            val state = coordinator.execute(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "hi",
                    mode = AgentMode.REACT,
                    metadata = mapOf("channel" to "web")
                ),
                hookContext = hookContext,
                toolsUsed = mutableListOf(),
                emit = {}
            )

            assertTrue(state.streamSuccess) {
                "Guard/Hook 없는 기본 요청은 성공으로 완료되어야 한다"
            }
            val stageTimings = readStageTimings(hookContext)
            assertTrue(stageTimings.containsKey("queue_wait")) { "queue_wait 타이밍이 기록되어야 한다" }
            assertTrue(stageTimings.containsKey("guard")) { "guard 타이밍이 기록되어야 한다" }
            assertTrue(stageTimings.containsKey("before_hooks")) { "before_hooks 타이밍이 기록되어야 한다" }
            assertTrue(stageTimings.containsKey("history_load")) { "history_load 타이밍이 기록되어야 한다" }
            assertTrue(stageTimings.containsKey("rag_retrieval")) { "rag_retrieval 타이밍이 기록되어야 한다" }
            assertTrue(stageTimings.containsKey("tool_selection")) { "tool_selection 타이밍이 기록되어야 한다" }
            assertTrue(stageTimings.containsKey("agent_loop")) { "agent_loop 타이밍이 기록되어야 한다" }
            verify {
                metrics.recordStageLatency("queue_wait", any(), match { it["channel"] == "web" })
            }
            verify {
                metrics.recordStageLatency("agent_loop", any(), match { it["channel"] == "web" })
            }
        }

        @Test
        fun `STANDARD 모드에서 도구 선택 없이 실행되어야 한다`() = runTest {
            val capturedInitialTools = mutableListOf<List<Any>>()
            val loopExecutor = mockk<StreamingReActLoopExecutor>()
            coEvery {
                loopExecutor.execute(any(), any(), any(), capture(capturedInitialTools), any(), any(), any(), any(), any(), any())
            } returns StreamingLoopResult(success = true, collectedContent = "ok", lastIterationContent = "ok")

            val (coordinator, _) = buildCoordinator(loopExecutor = loopExecutor)

            coordinator.execute(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "hello",
                    mode = AgentMode.STANDARD
                ),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hello"),
                toolsUsed = mutableListOf(),
                emit = {}
            )

            assertTrue(capturedInitialTools.isNotEmpty()) {
                "루프 실행기가 호출되어야 한다"
            }
            assertTrue(capturedInitialTools.first().isEmpty()) {
                "STANDARD 모드에서는 도구 목록이 비어 있어야 한다"
            }
        }
    }

    @Nested
    inner class Guard거부 {

        @Test
        fun `Guard 거부 시 에러 이벤트를 emit하고 streamErrorCode를 GUARD_REJECTED로 설정해야 한다`() = runTest {
            val guard = mockk<RequestGuard>()
            coEvery {
                guard.guard(any<GuardCommand>())
            } returns GuardResult.Rejected(
                reason = "Blocked by rate limit",
                category = RejectionCategory.RATE_LIMITED,
                stage = "RateLimit"
            )

            val emitted = mutableListOf<String>()
            val (coordinator, _) = buildCoordinator(guard = guard)

            val state = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                emit = { emitted.add(it) }
            )

            assertFalse(state.streamSuccess) { "Guard 거부 시 스트리밍은 실패해야 한다" }
            assertEquals(AgentErrorCode.GUARD_REJECTED, state.streamErrorCode) {
                "Guard 거부 시 에러 코드는 GUARD_REJECTED여야 한다"
            }
            val errorMarkers = emitted
                .mapNotNull { StreamEventMarker.parse(it) }
                .filter { it.first == "error" }
            assertTrue(errorMarkers.isNotEmpty()) {
                "Guard 거부 시 error 이벤트가 emit되어야 한다, 실제 emit: $emitted"
            }
            assertTrue(errorMarkers.any { it.second.contains("Blocked by rate limit") }) {
                "에러 이벤트에 거부 사유가 포함되어야 한다"
            }
        }

        @Test
        fun `RATE_LIMITED 카테고리 Guard 거부 시 GUARD_REJECTED로 분류해야 한다`() = runTest {
            val guard = mockk<RequestGuard>()
            coEvery {
                guard.guard(any<GuardCommand>())
            } returns GuardResult.Rejected(
                reason = "Rate limit exceeded",
                category = RejectionCategory.RATE_LIMITED,
                stage = "RateLimit"
            )

            val (coordinator, _) = buildCoordinator(guard = guard)

            val state = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                emit = {}
            )

            assertEquals(AgentErrorCode.GUARD_REJECTED, state.streamErrorCode) {
                "Rate limit 거부의 에러 코드는 GUARD_REJECTED여야 한다"
            }
        }
    }

    @Nested
    inner class Hook거부 {

        @Test
        fun `BeforeStart 훅 거부 시 에러 이벤트를 emit하고 HOOK_REJECTED를 설정해야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>()
            coEvery {
                hookExecutor.executeBeforeAgentStart(any())
            } returns HookResult.Reject("tenant not allowed")
            coEvery {
                hookExecutor.executeAfterAgentComplete(any(), any())
            } returns Unit

            val emitted = mutableListOf<String>()
            val (coordinator, _) = buildCoordinator(hookExecutor = hookExecutor)

            val state = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                emit = { emitted.add(it) }
            )

            assertFalse(state.streamSuccess) { "훅 거부 시 스트리밍은 실패해야 한다" }
            assertEquals(AgentErrorCode.HOOK_REJECTED, state.streamErrorCode) {
                "훅 거부 시 에러 코드는 HOOK_REJECTED여야 한다"
            }
            val errorMarkers = emitted
                .mapNotNull { StreamEventMarker.parse(it) }
                .filter { it.first == "error" }
            assertTrue(errorMarkers.isNotEmpty()) {
                "훅 거부 시 error 이벤트가 emit되어야 한다, 실제 emit: $emitted"
            }
            assertTrue(errorMarkers.any { it.second.contains("tenant not allowed") }) {
                "에러 이벤트에 훅 거부 사유가 포함되어야 한다"
            }
        }
    }

    @Nested
    inner class 구조화출력거부 {

        @Test
        fun `JSON 응답 형식 요청 시 에러 이벤트를 emit하고 INVALID_RESPONSE를 설정해야 한다`() = runTest {
            val emitted = mutableListOf<String>()
            val (coordinator, _) = buildCoordinator()

            val state = coordinator.execute(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "hi",
                    responseFormat = ResponseFormat.JSON
                ),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                emit = { emitted.add(it) }
            )

            assertFalse(state.streamSuccess) {
                "구조화 출력 형식은 스트리밍에서 지원하지 않으므로 실패해야 한다"
            }
            assertEquals(AgentErrorCode.INVALID_RESPONSE, state.streamErrorCode) {
                "JSON 응답 형식의 에러 코드는 INVALID_RESPONSE여야 한다"
            }
            val errorMarkers = emitted
                .mapNotNull { StreamEventMarker.parse(it) }
                .filter { it.first == "error" }
            assertTrue(errorMarkers.isNotEmpty()) {
                "구조화 출력 거부 시 error 이벤트가 emit되어야 한다, 실제 emit: $emitted"
            }
            assertTrue(
                errorMarkers.any { it.second.contains("streaming", ignoreCase = true) }
            ) { "에러 메시지에 스트리밍 미지원 사유가 포함되어야 한다" }
        }

        @Test
        fun `YAML 응답 형식 요청 시에도 INVALID_RESPONSE를 설정해야 한다`() = runTest {
            val (coordinator, _) = buildCoordinator()

            val state = coordinator.execute(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "hi",
                    responseFormat = ResponseFormat.YAML
                ),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                emit = {}
            )

            assertEquals(AgentErrorCode.INVALID_RESPONSE, state.streamErrorCode) {
                "YAML 응답 형식의 에러 코드는 INVALID_RESPONSE여야 한다"
            }
        }
    }

    @Nested
    inner class 예외처리 {

        @Test
        fun `예상치 못한 예외 발생 시 에러 이벤트를 emit하고 에러 코드를 분류해야 한다`() = runTest {
            val loopExecutor = mockk<StreamingReActLoopExecutor>()
            coEvery {
                loopExecutor.execute(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } throws RuntimeException("context length exceeded")

            val emitted = mutableListOf<String>()
            val (coordinator, _) = buildCoordinator(loopExecutor = loopExecutor)

            val state = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                emit = { emitted.add(it) }
            )

            assertFalse(state.streamSuccess) {
                "루프 실행기 예외 발생 시 스트리밍은 실패해야 한다"
            }
            assertEquals(AgentErrorCode.CONTEXT_TOO_LONG, state.streamErrorCode) {
                "context length 메시지를 포함한 예외는 CONTEXT_TOO_LONG으로 분류되어야 한다"
            }
            val errorMarkers = emitted
                .mapNotNull { StreamEventMarker.parse(it) }
                .filter { it.first == "error" }
            assertTrue(errorMarkers.isNotEmpty()) {
                "예외 발생 시 error 이벤트가 emit되어야 한다, 실제 emit: $emitted"
            }
        }

        @Test
        fun `알 수 없는 예외 발생 시 UNKNOWN 에러 코드를 반환해야 한다`() = runTest {
            val loopExecutor = mockk<StreamingReActLoopExecutor>()
            coEvery {
                loopExecutor.execute(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } throws IllegalStateException("unexpected state")

            val (coordinator, _) = buildCoordinator(loopExecutor = loopExecutor)

            val state = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                emit = {}
            )

            assertFalse(state.streamSuccess) {
                "예외 발생 시 스트리밍은 실패해야 한다"
            }
            assertEquals(AgentErrorCode.UNKNOWN, state.streamErrorCode) {
                "분류 불가 예외는 UNKNOWN 에러 코드를 반환해야 한다"
            }
        }

        @Test
        fun `rate limit 예외 발생 시 RATE_LIMITED 에러 코드를 반환해야 한다`() = runTest {
            val loopExecutor = mockk<StreamingReActLoopExecutor>()
            coEvery {
                loopExecutor.execute(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } throws RuntimeException("rate limit exceeded: too many requests")

            val (coordinator, _) = buildCoordinator(loopExecutor = loopExecutor)

            val state = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                emit = {}
            )

            assertEquals(AgentErrorCode.RATE_LIMITED, state.streamErrorCode) {
                "rate limit 메시지를 포함한 예외는 RATE_LIMITED로 분류되어야 한다"
            }
        }
    }

    @Nested
    inner class 타임아웃 {

        @Test
        fun `요청 타임아웃 발생 시 에러 이벤트를 emit하고 TIMEOUT을 설정해야 한다`() = runTest {
            val loopExecutor = mockk<StreamingReActLoopExecutor>()
            coEvery {
                loopExecutor.execute(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                // 코루틴이 취소될 때까지 무한 대기하여 타임아웃을 유도한다
                kotlinx.coroutines.suspendCancellableCoroutine<StreamingLoopResult> { }
            }

            val emitted = mutableListOf<String>()
            // requestTimeoutMs=1L 로 설정하여 즉시 타임아웃 발생
            val (coordinator, _) = buildCoordinator(
                loopExecutor = loopExecutor,
                requestTimeoutMs = 1L
            )

            val state = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                emit = { emitted.add(it) }
            )

            assertFalse(state.streamSuccess) {
                "타임아웃 발생 시 스트리밍은 실패해야 한다"
            }
            assertEquals(AgentErrorCode.TIMEOUT, state.streamErrorCode) {
                "타임아웃 에러 코드는 TIMEOUT이어야 한다"
            }
            val errorMarkers = emitted
                .mapNotNull { StreamEventMarker.parse(it) }
                .filter { it.first == "error" }
            assertTrue(errorMarkers.isNotEmpty()) {
                "타임아웃 발생 시 error 이벤트가 emit되어야 한다, 실제 emit: $emitted"
            }
        }
    }

    @Nested
    inner class 수집된콘텐츠 {

        @Test
        fun `루프에서 수집된 콘텐츠가 state에 누적되어야 한다`() = runTest {
            val loopExecutor = mockk<StreamingReActLoopExecutor>()
            coEvery {
                loopExecutor.execute(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns StreamingLoopResult(
                success = true,
                collectedContent = "streaming response text",
                lastIterationContent = "streaming response text"
            )

            val (coordinator, _) = buildCoordinator(loopExecutor = loopExecutor)

            val state = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "r", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                emit = {}
            )

            assertTrue(state.streamSuccess) { "루프가 성공한 경우 state도 성공이어야 한다" }
            assertEquals("streaming response text", state.collectedContent.toString()) {
                "루프에서 수집된 콘텐츠가 state에 저장되어야 한다"
            }
            assertEquals("streaming response text", state.lastIterationContent.toString()) {
                "마지막 반복 콘텐츠도 state에 저장되어야 한다"
            }
        }
    }
}
