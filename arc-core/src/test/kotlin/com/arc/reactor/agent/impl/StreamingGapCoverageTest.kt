package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.budget.StepBudgetTracker
import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.memory.TokenEstimator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.ChatOptions
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger

/**
 * 스트리밍 경로의 커버리지 GAP을 보강하는 테스트.
 *
 * 대상:
 * 1. [StreamingCompletionFinalizer] — 출력 가드 Allowed/Modified/Rejected 경로 및 미시작 실패 경로
 * 2. [StreamingExecutionCoordinator] — BlockedIntent 경로 + BUDGET_EXHAUSTED 에러코드
 * 3. [StreamingReActLoopExecutor] — 여러 도구 호출 누적 검증
 * 4. [unwrapReactorException] 유닛 테스트
 * 5. [StreamingCompletionFinalizer] AfterAgentComplete 훅 실패 시 fail-open 검증
 */
class StreamingGapCoverageTest {

    // =========================================================================
    // 1. StreamingCompletionFinalizer — 출력 가드 경로
    // =========================================================================
    @Nested
    inner class StreamingOutputGuard {

        /**
         * OutputGuardResult.Allowed 경로 — 히스토리 저장, 에러 emit 없음
         */
        @Test
        fun `출력 가드 Allowed 결과 시 히스토리를 저장하고 에러를 emit하지 않아야 한다`() = runTest {
            val conversationManager = mockk<ConversationManager>(relaxed = true)
            val metrics = mockk<AgentMetrics>(relaxed = true)

            val allowingStage = object : OutputGuardStage {
                override val stageName = "allow-all"
                override val order = 100
                override suspend fun check(
                    content: String,
                    context: OutputGuardContext
                ): OutputGuardResult = OutputGuardResult.Allowed.DEFAULT
            }

            val finalizer = StreamingCompletionFinalizer(
                boundaries = BoundaryProperties(),
                conversationManager = conversationManager,
                hookExecutor = null,
                agentMetrics = metrics,
                outputGuardPipeline = OutputGuardPipeline(listOf(allowingStage))
            )

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
            val emitted = mutableListOf<String>()

            finalizer.finalize(
                command = command,
                hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi"),
                streamStarted = true,
                streamSuccess = true,
                collectedContent = "안전한 응답",
                lastIterationContent = "안전한 응답",
                streamErrorMessage = null,
                streamErrorCode = null,
                toolsUsed = emptyList(),
                startTime = 1_000L,
                emit = { emitted.add(it) }
            )

            coVerify(exactly = 1) { conversationManager.saveStreamingHistory(command, "안전한 응답") }
            val errorMarkers = emitted.mapNotNull { StreamEventMarker.parse(it) }.filter { it.first == "error" }
            assertTrue(errorMarkers.isEmpty()) {
                "출력 가드 Allowed 결과 시 에러 마커가 emit되면 안 된다, 실제 emit: $emitted"
            }
            verify(exactly = 1) { metrics.recordOutputGuardAction("pipeline", "allowed", "", any()) }
        }

        /**
         * OutputGuardResult.Modified 경로 — 경고 emit, 히스토리 저장
         */
        @Test
        fun `출력 가드 Modified 결과 시 경고 마커를 emit하고 히스토리를 저장해야 한다`() = runTest {
            val conversationManager = mockk<ConversationManager>(relaxed = true)
            val metrics = mockk<AgentMetrics>(relaxed = true)

            val modifyingStage = object : OutputGuardStage {
                override val stageName = "pii-masker"
                override val order = 100
                override suspend fun check(
                    content: String,
                    context: OutputGuardContext
                ): OutputGuardResult = OutputGuardResult.Modified(
                    content = "마스킹된 응답",
                    reason = "PII 감지",
                    stage = "pii-masker"
                )
            }

            val finalizer = StreamingCompletionFinalizer(
                boundaries = BoundaryProperties(),
                conversationManager = conversationManager,
                hookExecutor = null,
                agentMetrics = metrics,
                outputGuardPipeline = OutputGuardPipeline(listOf(modifyingStage))
            )

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
            val emitted = mutableListOf<String>()

            finalizer.finalize(
                command = command,
                hookContext = HookContext(runId = "r2", userId = "u", userPrompt = "hi"),
                streamStarted = true,
                streamSuccess = true,
                collectedContent = "원본 응답 010-1234-5678",
                lastIterationContent = "원본 응답 010-1234-5678",
                streamErrorMessage = null,
                streamErrorCode = null,
                toolsUsed = emptyList(),
                startTime = 1_000L,
                emit = { emitted.add(it) }
            )

            // Modified → guardPassed=true → 원본 lastIterationContent로 히스토리 저장
            coVerify(exactly = 1) { conversationManager.saveStreamingHistory(command, any()) }
            val errorMarkers = emitted.mapNotNull { StreamEventMarker.parse(it) }.filter { it.first == "error" }
            assertTrue(errorMarkers.isNotEmpty()) {
                "출력 가드 Modified 결과 시 경고 마커가 emit되어야 한다, 실제 emit: $emitted"
            }
            assertTrue(errorMarkers.any { it.second.contains("Output guard modified") }) {
                "경고 마커에 'Output guard modified' 메시지가 포함되어야 한다, markers: $errorMarkers"
            }
            verify(exactly = 1) { metrics.recordOutputGuardAction("pii-masker", "modified", "PII 감지", any()) }
        }

        /**
         * OutputGuardResult.Rejected 경로 — 에러 emit, 빈 콘텐츠로 히스토리 저장
         */
        @Test
        fun `출력 가드 Rejected 결과 시 에러 마커를 emit하고 빈 히스토리를 저장해야 한다`() = runTest {
            val conversationManager = mockk<ConversationManager>(relaxed = true)
            val metrics = mockk<AgentMetrics>(relaxed = true)

            val rejectingStage = object : OutputGuardStage {
                override val stageName = "harm-detector"
                override val order = 100
                override suspend fun check(
                    content: String,
                    context: OutputGuardContext
                ): OutputGuardResult = OutputGuardResult.Rejected(
                    reason = "유해 콘텐츠 감지",
                    category = OutputRejectionCategory.HARMFUL_CONTENT,
                    stage = "harm-detector"
                )
            }

            val finalizer = StreamingCompletionFinalizer(
                boundaries = BoundaryProperties(),
                conversationManager = conversationManager,
                hookExecutor = null,
                agentMetrics = metrics,
                outputGuardPipeline = OutputGuardPipeline(listOf(rejectingStage))
            )

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
            val emitted = mutableListOf<String>()

            finalizer.finalize(
                command = command,
                hookContext = HookContext(runId = "r3", userId = "u", userPrompt = "hi"),
                streamStarted = true,
                streamSuccess = true,
                collectedContent = "위험한 응답",
                lastIterationContent = "위험한 응답",
                streamErrorMessage = null,
                streamErrorCode = null,
                toolsUsed = emptyList(),
                startTime = 1_000L,
                emit = { emitted.add(it) }
            )

            // Rejected → guardPassed=false → effectiveContent="" → 빈 히스토리 저장
            coVerify(exactly = 1) { conversationManager.saveStreamingHistory(command, "") }
            val errorMarkers = emitted.mapNotNull { StreamEventMarker.parse(it) }.filter { it.first == "error" }
            assertTrue(errorMarkers.isNotEmpty()) {
                "출력 가드 Rejected 결과 시 에러 마커가 emit되어야 한다, 실제 emit: $emitted"
            }
            assertTrue(errorMarkers.any { it.second.contains("Output guard rejected") }) {
                "에러 마커에 'Output guard rejected' 메시지가 포함되어야 한다, markers: $errorMarkers"
            }
            verify(exactly = 1) {
                metrics.recordOutputGuardAction("harm-detector", "rejected", "유해 콘텐츠 감지", any())
            }
        }

        /**
         * streamStarted=false, streamSuccess=false — 히스토리 저장 없음
         */
        @Test
        fun `스트리밍 미시작 실패 시 히스토리를 저장하지 않아야 한다`() = runTest {
            val conversationManager = mockk<ConversationManager>(relaxed = true)

            val finalizer = StreamingCompletionFinalizer(
                boundaries = BoundaryProperties(),
                conversationManager = conversationManager,
                hookExecutor = null,
                agentMetrics = mockk(relaxed = true)
            )

            finalizer.finalize(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "r4", userId = "u", userPrompt = "hi"),
                streamStarted = false,
                streamSuccess = false,
                collectedContent = "",
                lastIterationContent = "",
                streamErrorMessage = "Guard rejected",
                streamErrorCode = "GUARD_REJECTED",
                toolsUsed = emptyList(),
                startTime = 1_000L,
                emit = {}
            )

            // streamStarted=false, streamSuccess=false → 어떠한 히스토리도 저장되면 안 됨
            coVerify(exactly = 0) { conversationManager.saveStreamingHistory(any(), any()) }
        }

        /**
         * 출력 가드 null 상태에서 빈 콘텐츠 — 빈 히스토리 저장
         */
        @Test
        fun `출력 가드 null이고 빈 콘텐츠일 때 빈 히스토리를 저장해야 한다`() = runTest {
            val conversationManager = mockk<ConversationManager>(relaxed = true)

            val finalizer = StreamingCompletionFinalizer(
                boundaries = BoundaryProperties(),
                conversationManager = conversationManager,
                hookExecutor = null,
                agentMetrics = mockk(relaxed = true),
                outputGuardPipeline = null
            )

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")

            finalizer.finalize(
                command = command,
                hookContext = HookContext(runId = "r5", userId = "u", userPrompt = "hi"),
                streamStarted = true,
                streamSuccess = true,
                collectedContent = "",
                lastIterationContent = "",
                streamErrorMessage = null,
                streamErrorCode = null,
                toolsUsed = emptyList(),
                startTime = 1_000L,
                emit = {}
            )

            coVerify(exactly = 1) { conversationManager.saveStreamingHistory(command, "") }
        }
    }

    // =========================================================================
    // 2. StreamingExecutionCoordinator — BlockedIntent + BUDGET_EXHAUSTED
    // =========================================================================
    @Nested
    inner class CoordinatorSpecialPaths {

        /** 테스트용 기본 코디네이터를 생성한다. */
        private fun buildCoordinator(
            loopExecutor: StreamingReActLoopExecutor = mockk<StreamingReActLoopExecutor>().also {
                coEvery {
                    it.execute(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
                } returns StreamingLoopResult(
                    success = true, collectedContent = "ok", lastIterationContent = "ok"
                )
            },
            intentResolver: IntentResolver? = null,
            blockedIntents: Set<String> = emptySet(),
            budgetTrackerFactory: () -> StepBudgetTracker? = { null },
            metrics: AgentMetrics = mockk(relaxed = true)
        ): StreamingExecutionCoordinator {
            val conversationManager = mockk<ConversationManager>().also {
                coEvery { it.loadHistory(any()) } returns emptyList<Message>()
            }
            return StreamingExecutionCoordinator(
                concurrencySemaphore = Semaphore(1),
                requestTimeoutMs = 0L,
                maxToolCallsLimit = 4,
                preExecutionResolver = PreExecutionResolver(
                    guard = null,
                    hookExecutor = null,
                    intentResolver = intentResolver,
                    blockedIntents = blockedIntents,
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
                agentMetrics = metrics,
                createBudgetTracker = budgetTrackerFactory
            )
        }

        @Test
        fun `BlockedIntentException 발생 시 GUARD_REJECTED 에러 코드와 에러 마커를 설정해야 한다`() = runTest {
            // PreExecutionResolver가 blockedIntents를 확인하도록 설정
            // intentResolver가 차단 인텐트 이름을 반환하면 BlockedIntentException을 throw함
            val intentResolver = mockk<IntentResolver>()
            coEvery { intentResolver.resolve(any(), any()) } throws BlockedIntentException("harmful-query")

            val emitted = mutableListOf<String>()
            val coordinator = buildCoordinator(
                intentResolver = intentResolver,
                blockedIntents = setOf("harmful-query")
            )

            val state = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "blocked content"),
                hookContext = HookContext(runId = "r10", userId = "u", userPrompt = "blocked content"),
                toolsUsed = mutableListOf(),
                emit = { emitted.add(it) }
            )

            assertFalse(state.streamSuccess) { "BlockedIntent 시 스트리밍은 실패해야 한다" }
            assertEquals(AgentErrorCode.GUARD_REJECTED, state.streamErrorCode) {
                "BlockedIntent의 에러 코드는 GUARD_REJECTED여야 한다"
            }
            val errorMarkers = emitted.mapNotNull { StreamEventMarker.parse(it) }.filter { it.first == "error" }
            assertTrue(errorMarkers.isNotEmpty()) {
                "BlockedIntent 시 error 마커가 emit되어야 한다, 실제 emit: $emitted"
            }
            assertTrue(errorMarkers.any { it.second.contains("차단") || it.second.contains("보안 정책") }) {
                "에러 메시지에 차단 사유가 포함되어야 한다, markers: $errorMarkers"
            }
        }

        @Test
        fun `루프 실패 후 budgetStatus=EXHAUSTED 시 BUDGET_EXHAUSTED 에러 코드를 설정해야 한다`() = runTest {
            val hookContext = HookContext(runId = "r11", userId = "u", userPrompt = "hi")

            val loopExecutor = mockk<StreamingReActLoopExecutor>()
            coEvery {
                loopExecutor.execute(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                // 루프 내에서 budgetStatus를 EXHAUSTED로 설정하는 동작 시뮬레이션
                hookContext.metadata["budgetStatus"] = "EXHAUSTED"
                StreamingLoopResult(success = false, collectedContent = "partial", lastIterationContent = "partial")
            }

            val coordinator = buildCoordinator(loopExecutor = loopExecutor)

            val state = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = hookContext,
                toolsUsed = mutableListOf(),
                emit = {}
            )

            assertFalse(state.streamSuccess) { "예산 소진 시 스트리밍은 실패해야 한다" }
            assertEquals(AgentErrorCode.BUDGET_EXHAUSTED, state.streamErrorCode) {
                "budgetStatus=EXHAUSTED 시 에러 코드는 BUDGET_EXHAUSTED여야 한다"
            }
            assertTrue(state.streamErrorMessage?.contains("소진") == true) {
                "에러 메시지에 예산 소진 내용이 포함되어야 한다, message: ${state.streamErrorMessage}"
            }
        }

        @Test
        fun `루프 성공 결과가 state에 정확히 반영되어야 한다`() = runTest {
            val loopExecutor = mockk<StreamingReActLoopExecutor>()
            coEvery {
                loopExecutor.execute(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns StreamingLoopResult(
                success = true,
                collectedContent = "전체 응답",
                lastIterationContent = "마지막 청크"
            )

            val coordinator = buildCoordinator(loopExecutor = loopExecutor)

            val state = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "r12", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                emit = {}
            )

            assertTrue(state.streamSuccess) { "루프 성공 시 state.streamSuccess는 true여야 한다" }
            assertEquals("전체 응답", state.collectedContent.toString()) {
                "수집된 콘텐츠가 state에 정확히 저장되어야 한다"
            }
            assertEquals("마지막 청크", state.lastIterationContent.toString()) {
                "마지막 반복 콘텐츠가 state에 정확히 저장되어야 한다"
            }
        }
    }

    // =========================================================================
    // 3. unwrapReactorException 유닛 테스트
    // =========================================================================
    @Nested
    inner class UnwrapReactorExceptionTest {

        @Test
        fun `RuntimeException에 cause가 있으면 cause를 반환해야 한다`() {
            val cause = IllegalArgumentException("original cause")
            val wrapped = RuntimeException("reactor wrapped", cause)

            val unwrapped = unwrapReactorException(wrapped)

            assertEquals(cause, unwrapped) {
                "cause가 있는 RuntimeException은 cause를 반환해야 한다"
            }
        }

        @Test
        fun `RuntimeException에 cause가 없으면 원본을 반환해야 한다`() {
            val exception = RuntimeException("no cause")

            val unwrapped = unwrapReactorException(exception)

            assertEquals(exception, unwrapped) {
                "cause가 없는 RuntimeException은 원본을 반환해야 한다"
            }
        }

        @Test
        fun `Error는 RuntimeException이 아니므로 cause가 있어도 원본을 반환해야 한다`() {
            val cause = RuntimeException("inner")
            // Error는 RuntimeException의 하위 클래스가 아님 — 원본 반환 기대
            val exception = Error("not a RuntimeException", cause)

            val unwrapped = unwrapReactorException(exception)

            assertEquals(exception, unwrapped) {
                "Error(Throwable)는 RuntimeException이 아니므로 cause가 있어도 원본을 반환해야 한다"
            }
        }

        @Test
        fun `중첩된 RuntimeException 래핑 시 첫 번째 cause만 벗겨야 한다`() {
            val deepCause = NullPointerException("deep")
            val innerCause = IllegalStateException("inner", deepCause)
            val wrapped = RuntimeException("reactor outer", innerCause)

            val unwrapped = unwrapReactorException(wrapped)

            assertEquals(innerCause, unwrapped) {
                "한 단계만 벗겨야 한다 — innerCause를 반환해야 한다"
            }
        }

        @Test
        fun `cause가 null인 RuntimeException은 원본을 반환해야 한다`() {
            val exception = RuntimeException("no cause at all", null)

            val unwrapped = unwrapReactorException(exception)

            assertEquals(exception, unwrapped) {
                "cause가 null인 RuntimeException은 원본을 반환해야 한다"
            }
        }

        @Test
        fun `Reactor 래핑 예외에서 context-too-long 원인을 올바르게 추출해야 한다`() {
            val originalCause = RuntimeException("context length exceeded: limit 8192")
            val reactorWrapped = RuntimeException("reactor.core.Exceptions propagation", originalCause)

            val unwrapped = unwrapReactorException(reactorWrapped)

            assertTrue(unwrapped.message?.contains("context length exceeded") == true) {
                "언래핑된 예외에 원래 메시지가 포함되어야 한다, message: ${unwrapped.message}"
            }
            assertEquals(originalCause, unwrapped) {
                "unwrapped는 원본 cause여야 한다"
            }
        }
    }

    // =========================================================================
    // 4. StreamingReActLoopExecutor — 다중 도구 호출 누적
    // =========================================================================
    @Nested
    inner class StreamingLoopMultiToolRound {

        @Test
        fun `두 라운드 도구 호출 후 성공적으로 완료되어야 한다`() = runTest {
            val toolCall1 = AssistantMessage.ToolCall("tc-1", "call", "search", "{}")
            val toolCall2 = AssistantMessage.ToolCall("tc-2", "call", "summarize", "{}")

            val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
            every { requestSpec.stream() } returns streamResponseSpec
            every { streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(AgentTestFixture.toolCallChunk(listOf(toolCall1), "검색 중...")),
                Flux.just(AgentTestFixture.toolCallChunk(listOf(toolCall2), "요약 중...")),
                Flux.just(AgentTestFixture.textChunk("최종 답변"))
            )

            val toolOrchestrator = mockk<ToolCallOrchestrator>()
            val totalCallsTracker = AtomicInteger(0)
            coEvery {
                toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                val counter = it.invocation.args[4] as AtomicInteger
                counter.incrementAndGet()
                totalCallsTracker.incrementAndGet()
                val pendingCalls = it.invocation.args[0] as List<*>
                pendingCalls.map { tc ->
                    val toolCall = tc as AssistantMessage.ToolCall
                    ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), "결과")
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

            val result = loopExecutor.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", maxToolCalls = 5),
                activeChatClient = mockk(relaxed = true),
                systemPrompt = "sys",
                initialTools = listOf(mockk<Any>(relaxed = true)),
                conversationHistory = emptyList(),
                hookContext = HookContext(runId = "r21", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                allowedTools = null,
                maxToolCalls = 5,
                emit = {}
            )

            assertTrue(result.success) { "두 라운드 도구 호출 후 성공적으로 완료되어야 한다" }
            assertTrue(result.collectedContent.contains("최종 답변")) {
                "최종 텍스트가 수집된 콘텐츠에 포함되어야 한다, 실제: ${result.collectedContent}"
            }
            assertEquals("최종 답변", result.lastIterationContent) {
                "마지막 반복 콘텐츠가 최종 텍스트여야 한다"
            }
            assertEquals(2, totalCallsTracker.get()) {
                "도구 실행이 정확히 2번 호출되어야 한다"
            }
        }
    }

    // =========================================================================
    // 5. StreamingCompletionFinalizer — AfterAgentComplete 훅 fail-open
    // =========================================================================
    @Nested
    inner class AfterCompleteHookFailOpen {

        @Test
        fun `AfterAgentComplete 훅 실패 시 예외를 삼키고 히스토리는 정상 저장되어야 한다`() = runTest {
            val conversationManager = mockk<ConversationManager>(relaxed = true)
            val hookExecutor = mockk<HookExecutor>()
            coEvery {
                hookExecutor.executeAfterAgentComplete(any(), any())
            } throws RuntimeException("after-hook DB failure")

            val finalizer = StreamingCompletionFinalizer(
                boundaries = BoundaryProperties(),
                conversationManager = conversationManager,
                hookExecutor = hookExecutor,
                agentMetrics = mockk(relaxed = true)
            )

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
            // RuntimeException이 상위로 전파되지 않아야 한다 (fail-open)
            finalizer.finalize(
                command = command,
                hookContext = HookContext(runId = "r30", userId = "u", userPrompt = "hi"),
                streamStarted = true,
                streamSuccess = true,
                collectedContent = "응답",
                lastIterationContent = "응답",
                streamErrorMessage = null,
                streamErrorCode = null,
                toolsUsed = emptyList(),
                startTime = 1_000L,
                emit = {}
            )

            // 훅 에러에도 히스토리가 정상 저장되어야 한다
            coVerify(exactly = 1) { conversationManager.saveStreamingHistory(command, "응답") }
        }

        @Test
        fun `스트리밍 실패 시 AfterAgentComplete에 에러 정보가 전달되어야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)

            val finalizer = StreamingCompletionFinalizer(
                boundaries = BoundaryProperties(),
                conversationManager = mockk(relaxed = true),
                hookExecutor = hookExecutor,
                agentMetrics = mockk(relaxed = true)
            )

            val hookContext = HookContext(runId = "r31", userId = "u", userPrompt = "hi")
            finalizer.finalize(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = hookContext,
                streamStarted = true,
                streamSuccess = false,
                collectedContent = "",
                lastIterationContent = "",
                streamErrorMessage = "Rate limit exceeded",
                streamErrorCode = "RATE_LIMITED",
                toolsUsed = emptyList(),
                startTime = 1_000L,
                emit = {}
            )

            coVerify(exactly = 1) {
                hookExecutor.executeAfterAgentComplete(
                    context = hookContext,
                    response = match {
                        !it.success &&
                            it.errorMessage == "Rate limit exceeded" &&
                            it.errorCode == "RATE_LIMITED"
                    }
                )
            }
        }
    }
}
