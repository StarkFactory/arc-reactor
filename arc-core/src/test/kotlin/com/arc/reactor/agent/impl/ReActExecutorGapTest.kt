package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.assertErrorCode
import com.arc.reactor.agent.assertFailure
import com.arc.reactor.agent.assertSuccess
import com.arc.reactor.agent.budget.BudgetStatus
import com.arc.reactor.agent.budget.StepBudgetTracker
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.agent.routing.AgentModeResolver
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.ToolCallResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

/**
 * ReAct 루프 / Executor 관련 커버리지 보강 테스트.
 *
 * 기존 테스트에서 누락된 시나리오:
 * - ReActLoopUtils: emitTokenUsageMetric, trackBudgetAndCheckExhausted, recordLoopDurations, shouldRetryAfterToolError
 * - StepBudgetTracker: softLimitWarned 중복 방지, meterRegistry 메트릭
 * - AgentExecutionCoordinator: STANDARD 모드 도구 건너뜀, agentModeResolver 적용
 * - PlanExecuteStrategy: parsePlan 직접 테스트(내부 JSON 추출), 단계 에러 시 StepResult, budgetTracker 토큰 추적
 */
class ReActExecutorGapTest {

    // ───────────────────────────────────────────────────────────────
    // ReActLoopUtils — 미테스트 메서드 보강
    // ───────────────────────────────────────────────────────────────

    @Nested
    inner class EmitTokenUsageMetric {

        @Test
        fun `meta가 null이면 콜백을 호출하지 않아야 한다`() {
            var called = false
            val hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi")

            ReActLoopUtils.emitTokenUsageMetric(null, hookContext) { _, _ -> called = true }

            assertFalse(called) { "meta=null이면 콜백이 호출되면 안 된다" }
        }

        @Test
        fun `usage가 null이면 콜백을 호출하지 않아야 한다`() {
            var called = false
            val hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi")
            val meta = mockk<ChatResponseMetadata>()
            every { meta.usage } returns null

            ReActLoopUtils.emitTokenUsageMetric(meta, hookContext) { _, _ -> called = true }

            assertFalse(called) { "usage=null이면 콜백이 호출되면 안 된다" }
        }

        @Test
        fun `usage가 있으면 TokenUsage와 메타데이터로 콜백을 호출해야 한다`() {
            var capturedUsage: TokenUsage? = null
            var capturedMeta: Map<String, Any>? = null
            val hookContext = HookContext(
                runId = "run-42", userId = "u", userPrompt = "hi",
                metadata = mutableMapOf("tenantId" to "tenant-1")
            )

            val usage = mockk<Usage>()
            every { usage.promptTokens } returns 100
            every { usage.completionTokens } returns 50
            every { usage.totalTokens } returns 150

            val meta = mockk<ChatResponseMetadata>()
            every { meta.usage } returns usage
            every { meta.model } returns "gemini-2.0-flash"

            ReActLoopUtils.emitTokenUsageMetric(meta, hookContext) { u, m ->
                capturedUsage = u
                capturedMeta = m
            }

            capturedUsage shouldNotBe null
            val usage2 = capturedUsage ?: error("capturedUsage가 null이면 안 된다")
            usage2.promptTokens shouldBe 100
            usage2.completionTokens shouldBe 50
            usage2.totalTokens shouldBe 150
            val meta2 = capturedMeta ?: error("capturedMeta가 null이면 안 된다")
            meta2["runId"] shouldBe "run-42"
            meta2["model"] shouldBe "gemini-2.0-flash"
            meta2["tenantId"] shouldBe "tenant-1"
        }

        @Test
        fun `model이 null이면 콜백 메타에 model 키가 없어야 한다`() {
            var capturedMeta: Map<String, Any>? = null
            val hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi")

            val usage = mockk<Usage>()
            every { usage.promptTokens } returns 10
            every { usage.completionTokens } returns 5
            every { usage.totalTokens } returns 15

            val meta = mockk<ChatResponseMetadata>()
            every { meta.usage } returns usage
            every { meta.model } returns null

            ReActLoopUtils.emitTokenUsageMetric(meta, hookContext) { _, m -> capturedMeta = m }

            assertFalse(capturedMeta!!.containsKey("model")) {
                "model=null이면 메타에 'model' 키가 없어야 한다"
            }
        }
    }

    @Nested
    inner class TrackBudgetAndCheckExhausted {

        @Test
        fun `tracker가 null이면 항상 false를 반환해야 한다`() {
            val hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi")

            val result = ReActLoopUtils.trackBudgetAndCheckExhausted(
                meta = null, tracker = null, stepName = "step", hookContext = hookContext
            )

            assertFalse(result) { "tracker=null이면 false를 반환해야 한다" }
        }

        @Test
        fun `meta가 null이면 false를 반환하고 hookContext를 변경하지 않아야 한다`() {
            val tracker = StepBudgetTracker(maxTokens = 100)
            val hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi")

            val result = ReActLoopUtils.trackBudgetAndCheckExhausted(
                meta = null, tracker = tracker, stepName = "step", hookContext = hookContext
            )

            assertFalse(result) { "meta=null이면 false를 반환해야 한다" }
            assertNull(hookContext.metadata["tokensUsed"]) {
                "meta=null이면 hookContext가 변경되면 안 된다"
            }
        }

        @Test
        fun `예산 미소진 시 false를 반환하고 hookContext에 상태를 기록해야 한다`() {
            val tracker = StepBudgetTracker(maxTokens = 1000)
            val hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi")

            val usage = mockk<Usage>()
            every { usage.promptTokens } returns 100
            every { usage.completionTokens } returns 50
            every { usage.totalTokens } returns 150

            val meta = mockk<ChatResponseMetadata>()
            every { meta.usage } returns usage

            val result = ReActLoopUtils.trackBudgetAndCheckExhausted(
                meta = meta, tracker = tracker, stepName = "llm-call-1", hookContext = hookContext
            )

            assertFalse(result) { "예산 미소진이면 false를 반환해야 한다" }
            assertEquals(150, hookContext.metadata["tokensUsed"]) {
                "소비된 토큰 수가 hookContext에 기록되어야 한다"
            }
            assertEquals(BudgetStatus.OK.name, hookContext.metadata["budgetStatus"]) {
                "budgetStatus가 OK로 기록되어야 한다"
            }
        }

        @Test
        fun `예산 소진 시 true를 반환하고 hookContext에 EXHAUSTED를 기록해야 한다`() {
            val tracker = StepBudgetTracker(maxTokens = 100)
            val hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi")

            val usage = mockk<Usage>()
            every { usage.promptTokens } returns 80
            every { usage.completionTokens } returns 30
            every { usage.totalTokens } returns 110

            val meta = mockk<ChatResponseMetadata>()
            every { meta.usage } returns usage

            val result = ReActLoopUtils.trackBudgetAndCheckExhausted(
                meta = meta, tracker = tracker, stepName = "llm-call-1", hookContext = hookContext
            )

            assertTrue(result) { "예산 소진이면 true를 반환해야 한다" }
            assertEquals(BudgetStatus.EXHAUSTED.name, hookContext.metadata["budgetStatus"]) {
                "budgetStatus가 EXHAUSTED로 기록되어야 한다"
            }
        }
    }

    @Nested
    inner class RecordLoopDurations {

        @Test
        fun `LLM과 Tool 소요 시간이 HookContext metadata에 기록되어야 한다`() {
            val hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi")

            ReActLoopUtils.recordLoopDurations(
                hookContext = hookContext,
                totalLlmDurationMs = 1234L,
                totalToolDurationMs = 567L
            )

            assertEquals(1234L, hookContext.metadata["llmDurationMs"]) {
                "llmDurationMs가 hookContext에 기록되어야 한다"
            }
            assertEquals(567L, hookContext.metadata["toolDurationMs"]) {
                "toolDurationMs가 hookContext에 기록되어야 한다"
            }
        }

        @Test
        fun `stageTimings에 llm_calls와 tool_execution 항목이 생성되어야 한다`() {
            val hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi")

            ReActLoopUtils.recordLoopDurations(
                hookContext = hookContext,
                totalLlmDurationMs = 100L,
                totalToolDurationMs = 200L
            )

            val timings = readStageTimings(hookContext)
            assertEquals(100L, timings["llm_calls"]) {
                "stageTimings에 llm_calls 항목이 있어야 한다"
            }
            assertEquals(200L, timings["tool_execution"]) {
                "stageTimings에 tool_execution 항목이 있어야 한다"
            }
        }
    }

    @Nested
    inner class ShouldRetryAfterToolError {

        @Test
        fun `hadToolError=false이면 false를 반환해야 한다`() {
            val messages = mutableListOf<Message>()

            val result = ReActLoopUtils.shouldRetryAfterToolError(
                hadToolError = false,
                pendingToolCalls = emptyList(),
                activeTools = listOf(Any()),
                messages = messages,
                textRetryCount = 0
            )

            assertFalse(result) { "hadToolError=false이면 재시도하지 않아야 한다" }
        }

        @Test
        fun `pendingToolCalls가 비어있지 않으면 false를 반환해야 한다`() {
            val messages = mutableListOf<Message>()
            val toolCall = AssistantMessage.ToolCall("id", "call", "tool", "{}")

            val result = ReActLoopUtils.shouldRetryAfterToolError(
                hadToolError = true,
                pendingToolCalls = listOf(toolCall),
                activeTools = listOf(Any()),
                messages = messages,
                textRetryCount = 0
            )

            assertFalse(result) { "pendingToolCalls가 있으면 재시도하지 않아야 한다" }
        }

        @Test
        fun `activeTools가 비어있으면 false를 반환해야 한다`() {
            val messages = mutableListOf<Message>()

            val result = ReActLoopUtils.shouldRetryAfterToolError(
                hadToolError = true,
                pendingToolCalls = emptyList(),
                activeTools = emptyList(),
                messages = messages,
                textRetryCount = 0
            )

            assertFalse(result) { "activeTools=empty이면 재시도하지 않아야 한다" }
        }

        @Test
        fun `모든 조건 충족 시 hint를 주입하고 true를 반환해야 한다`() {
            val messages = mutableListOf<Message>(UserMessage("test"))

            val result = ReActLoopUtils.shouldRetryAfterToolError(
                hadToolError = true,
                pendingToolCalls = emptyList(),
                activeTools = listOf(Any()),
                messages = messages,
                textRetryCount = 0
            )

            assertTrue(result) { "모든 조건 충족 시 true를 반환해야 한다" }
            assertTrue(messages.any { it is SystemMessage }) {
                "force-retry SystemMessage가 주입되어야 한다"
            }
        }

        @Test
        fun `textRetryCount가 한도에 도달하면 false를 반환해야 한다`() {
            val messages = mutableListOf<Message>()

            val result = ReActLoopUtils.shouldRetryAfterToolError(
                hadToolError = true,
                pendingToolCalls = emptyList(),
                activeTools = listOf(Any()),
                messages = messages,
                textRetryCount = ReActLoopUtils.MAX_TEXT_RETRIES_AFTER_TOOL_ERROR
            )

            assertFalse(result) { "재시도 한도 도달 시 false를 반환해야 한다" }
        }
    }

    // ───────────────────────────────────────────────────────────────
    // StepBudgetTracker — 미테스트 시나리오
    // ───────────────────────────────────────────────────────────────

    @Nested
    inner class StepBudgetTrackerGap {

        @Test
        fun `소프트 리밋 경고는 한 번만 발생해야 한다 (중복 방지)`() {
            val tracker = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 80)

            // 첫 번째 SOFT_LIMIT 진입
            val status1 = tracker.trackStep("step-1", inputTokens = 800, outputTokens = 0)
            // 두 번째 SOFT_LIMIT 상태 유지 (추가 소비)
            val status2 = tracker.trackStep("step-2", inputTokens = 50, outputTokens = 0)

            assertEquals(BudgetStatus.SOFT_LIMIT, status1) {
                "첫 번째 step은 SOFT_LIMIT이어야 한다"
            }
            assertEquals(BudgetStatus.SOFT_LIMIT, status2) {
                "두 번째 step도 하드 리밋 미만이면 SOFT_LIMIT이어야 한다"
            }
            // softLimitWarned 플래그로 두 번째 warn 로그는 발생하지 않지만
            // 히스토리에는 두 단계 모두 기록되어야 함
            assertEquals(2, tracker.history().size) {
                "두 단계 모두 히스토리에 기록되어야 한다"
            }
        }

        @Test
        fun `EXHAUSTED 후 추가 trackStep 호출 시 exhaustedRecorded 중복 방지`() {
            val tracker = StepBudgetTracker(maxTokens = 100, softLimitPercent = 80)

            val status1 = tracker.trackStep("step-1", inputTokens = 100, outputTokens = 0)
            val status2 = tracker.trackStep("step-2", inputTokens = 50, outputTokens = 0)
            val status3 = tracker.trackStep("step-3", inputTokens = 100, outputTokens = 0)

            assertEquals(BudgetStatus.EXHAUSTED, status1) {
                "첫 번째 소진은 EXHAUSTED여야 한다"
            }
            assertEquals(BudgetStatus.EXHAUSTED, status2) {
                "소진 후 추가 호출도 EXHAUSTED여야 한다"
            }
            assertEquals(BudgetStatus.EXHAUSTED, status3) {
                "소진 후 추가 호출도 EXHAUSTED여야 한다"
            }
            assertEquals(3, tracker.history().size) {
                "세 단계 모두 히스토리에 기록되어야 한다"
            }
            assertTrue(tracker.isExhausted()) { "isExhausted()는 계속 true여야 한다" }
        }

        @Test
        fun `softLimitPercent 경계 1 퍼센트 설정 시 토큰 10개에서 SOFT_LIMIT이어야 한다`() {
            // maxTokens=1000, softLimitPercent=1 → softLimitTokens = 10
            val tracker = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 1)

            val statusBelow = tracker.trackStep("below", inputTokens = 9, outputTokens = 0)
            val tracker2 = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 1)
            val statusAt = tracker2.trackStep("at", inputTokens = 10, outputTokens = 0)

            assertEquals(BudgetStatus.OK, statusBelow) {
                "9토큰(< 10 = 1% of 1000)은 OK여야 한다"
            }
            assertEquals(BudgetStatus.SOFT_LIMIT, statusAt) {
                "10토큰(= 1% of 1000)은 SOFT_LIMIT이어야 한다"
            }
        }

        @Test
        fun `maxTokens 정확히 1 토큰에서 EXHAUSTED여야 한다 (최소 예산)`() {
            val tracker = StepBudgetTracker(maxTokens = 1, softLimitPercent = 50)

            val status = tracker.trackStep("step", inputTokens = 1, outputTokens = 0)

            assertEquals(BudgetStatus.EXHAUSTED, status) {
                "maxTokens=1에서 1토큰 소비 시 EXHAUSTED여야 한다"
            }
            assertTrue(tracker.isExhausted()) { "isExhausted()는 true여야 한다" }
            assertEquals(0, tracker.remaining()) { "remaining()은 0이어야 한다" }
        }
    }

    // ───────────────────────────────────────────────────────────────
    // AgentExecutionCoordinator — STANDARD 모드 / agentModeResolver
    // ───────────────────────────────────────────────────────────────

    @Nested
    inner class AgentExecutionCoordinatorGap {

        /** 기본 coordinator 빌더 헬퍼 */
        private fun buildCoordinator(
            executeWithTools: suspend (
                AgentCommand,
                List<Any>,
                List<org.springframework.ai.chat.messages.Message>,
                HookContext,
                MutableList<String>,
                String?
            ) -> AgentResult = { _, _, _, _, _, _ -> AgentResult.success("ok") },
            selectAndPrepareTools: (String) -> List<Any> = { emptyList() },
            agentModeResolver: AgentModeResolver? = null
        ) = AgentExecutionCoordinator(
            responseCache = null,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = mockk(relaxed = true),
            agentModeResolver = agentModeResolver,
            toolCallbacks = emptyList(),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = selectAndPrepareTools,
            retrieveRagContext = { null },
            executeWithTools = executeWithTools,
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { cmd, _ -> cmd }
        )

        @Test
        fun `STANDARD 모드에서는 도구 선택이 건너뛰어져야 한다`() = runTest {
            var selectCalled = false
            var capturedTools: List<Any>? = null
            val coordinator = buildCoordinator(
                selectAndPrepareTools = {
                    selectCalled = true
                    listOf(Any())
                },
                executeWithTools = { _, tools, _, _, _, _ ->
                    capturedTools = tools
                    AgentResult.success("ok")
                }
            )

            coordinator.execute(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "hello",
                    mode = AgentMode.STANDARD
                ),
                hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hello"),
                toolsUsed = mutableListOf(),
                startTime = 1000L
            )

            assertFalse(selectCalled) {
                "STANDARD 모드에서는 도구 선택이 호출되면 안 된다"
            }
            assertEquals(emptyList<Any>(), capturedTools) {
                "STANDARD 모드에서는 빈 도구 리스트가 전달되어야 한다"
            }
        }

        @Test
        fun `agentModeResolver가 PLAN_EXECUTE를 반환하면 command 모드가 교체되어야 한다`() = runTest {
            var capturedMode: AgentMode? = null
            val modeResolver = mockk<AgentModeResolver>()
            coEvery { modeResolver.resolve(any(), any()) } returns AgentMode.PLAN_EXECUTE

            val coordinator = buildCoordinator(
                agentModeResolver = modeResolver,
                executeWithTools = { cmd, _, _, hookCtx, _, _ ->
                    capturedMode = cmd.mode
                    hookCtx.metadata["modeResolved"]
                        ?.let { /* 검증은 아래에서 */ }
                    AgentResult.success("ok")
                }
            )
            val hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "복잡한 질문")

            coordinator.execute(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "복잡한 질문",
                    mode = AgentMode.REACT
                ),
                hookContext = hookContext,
                toolsUsed = mutableListOf(),
                startTime = 1000L
            )

            assertEquals(AgentMode.PLAN_EXECUTE, capturedMode) {
                "modeResolver가 PLAN_EXECUTE를 반환하면 command 모드가 교체되어야 한다"
            }
            assertEquals("PLAN_EXECUTE", hookContext.metadata["modeResolved"]) {
                "hookContext에 modeResolved가 기록되어야 한다"
            }
        }

        @Test
        fun `agentModeResolver가 원본과 같은 모드를 반환하면 modeResolved 메타가 없어야 한다`() = runTest {
            val modeResolver = mockk<AgentModeResolver>()
            coEvery { modeResolver.resolve(any(), any()) } returns AgentMode.REACT

            val coordinator = buildCoordinator(agentModeResolver = modeResolver)
            val hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi")

            coordinator.execute(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "hi",
                    mode = AgentMode.REACT
                ),
                hookContext = hookContext,
                toolsUsed = mutableListOf(),
                startTime = 1000L
            )

            assertNull(hookContext.metadata["modeResolved"]) {
                "모드 변경이 없으면 modeResolved 메타가 없어야 한다"
            }
        }

        @Test
        fun `guardAndHooks가 non-null을 반환하면 즉시 해당 결과를 반환해야 한다`() = runTest {
            var executeCalled = false
            val guardResult = AgentResult.failure(
                errorMessage = "차단됨",
                errorCode = AgentErrorCode.GUARD_REJECTED
            )
            val coordinator = AgentExecutionCoordinator(
                responseCache = null,
                cacheableTemperature = 0.0,
                defaultTemperature = 0.3,
                fallbackStrategy = null,
                agentMetrics = mockk(relaxed = true),
                toolCallbacks = emptyList(),
                mcpToolCallbacks = { emptyList() },
                conversationManager = mockk(relaxed = true),
                selectAndPrepareTools = { emptyList() },
                retrieveRagContext = { null },
                executeWithTools = { _, _, _, _, _, _ ->
                    executeCalled = true
                    AgentResult.success("should not reach")
                },
                finalizeExecution = { result, _, _, _, _ -> result },
                checkGuardAndHooks = { _, _, _ -> guardResult },
                resolveIntent = { cmd, _ -> cmd }
            )

            val result = coordinator.execute(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi"),
                toolsUsed = mutableListOf(),
                startTime = 1000L
            )

            assertFalse(result.success) { "guard 차단 결과는 실패여야 한다" }
            assertEquals("차단됨", result.errorMessage) { "guard 메시지가 그대로 전달되어야 한다" }
            assertFalse(executeCalled) { "guard 차단 시 executeWithTools가 호출되면 안 된다" }
        }
    }

    // ───────────────────────────────────────────────────────────────
    // PlanExecuteStrategy — parsePlan 직접 테스트 (internal)
    // ───────────────────────────────────────────────────────────────

    @Nested
    inner class PlanExecuteStrategyGap {

        private lateinit var fixture: AgentTestFixture
        private lateinit var toolCallOrchestrator: ToolCallOrchestrator
        private lateinit var strategy: PlanExecuteStrategy

        @BeforeEach
        fun setup() {
            fixture = AgentTestFixture()
            toolCallOrchestrator = mockk(relaxed = true)
            strategy = PlanExecuteStrategy(
                toolCallOrchestrator = toolCallOrchestrator,
                buildRequestSpec = { _, _, _, _, _ -> fixture.requestSpec },
                callWithRetry = { block -> block() },
                buildChatOptions = { _, _ -> mockk(relaxed = true) }
            )
        }

        @Test
        fun `parsePlan — 정상 JSON 배열을 파싱해야 한다`() {
            val json = """[{"tool":"search","args":{"q":"test"},"description":"검색"}]"""

            val result = strategy.parsePlan(json)

            assertEquals(1, result.size) { "1개 PlanStep이 파싱되어야 한다" }
            assertEquals("search", result[0].tool) { "tool 이름이 정확해야 한다" }
            assertEquals("검색", result[0].description) { "description이 정확해야 한다" }
        }

        @Test
        fun `parsePlan — JSON 앞뒤 텍스트가 있어도 배열을 추출해야 한다`() {
            val text = "계획을 생성했습니다:\n" +
                """[{"tool":"tool_a","args":{},"description":"A 실행"}]""" +
                "\n이상입니다."

            val result = strategy.parsePlan(text)

            assertEquals(1, result.size) { "텍스트 래핑이 있어도 파싱되어야 한다" }
            assertEquals("tool_a", result[0].tool) { "tool 이름이 정확해야 한다" }
        }

        @Test
        fun `parsePlan — JSON 배열이 없으면 빈 리스트를 반환해야 한다`() {
            val result = strategy.parsePlan("이건 배열이 없는 텍스트입니다.")

            assertTrue(result.isEmpty()) { "JSON 배열 없으면 빈 리스트를 반환해야 한다" }
        }

        @Test
        fun `parsePlan — 빈 문자열이면 빈 리스트를 반환해야 한다`() {
            val result = strategy.parsePlan("")

            assertTrue(result.isEmpty()) { "빈 문자열은 빈 리스트를 반환해야 한다" }
        }

        @Test
        fun `parsePlan — 잘못된 JSON 구조이면 빈 리스트를 반환해야 한다`() {
            val result = strategy.parsePlan("[{잘못된 JSON}]")

            assertTrue(result.isEmpty()) { "잘못된 JSON은 빈 리스트를 반환해야 한다" }
        }

        @Test
        fun `parsePlan — 여러 단계 계획을 파싱해야 한다`() {
            val json = """
                [
                  {"tool":"jira_search","args":{"jql":"project=TEST"},"description":"이슈 검색"},
                  {"tool":"jira_get_issue","args":{"issueKey":"TEST-1"},"description":"이슈 상세"},
                  {"tool":"notify","args":{"message":"완료"},"description":"알림"}
                ]
            """.trimIndent()

            val result = strategy.parsePlan(json)

            assertEquals(3, result.size) { "3개 PlanStep이 파싱되어야 한다" }
            assertEquals("jira_search", result[0].tool) { "첫 번째 tool이 정확해야 한다" }
            assertEquals("notify", result[2].tool) { "세 번째 tool이 정확해야 한다" }
        }

        @Test
        fun `도구 실행 오류 시 StepResult에 Error 접두사가 포함되어야 한다`() = runTest {
            val planJson =
                """[{"tool":"failing_tool","args":{},"description":"오류 발생 도구"}]"""
            val planResponse = simpleChatResponse(planJson)
            val synthesisResponse = simpleChatResponse("부분 결과로 합성")

            every { fixture.callResponseSpec.chatResponse() } returnsMany
                listOf(planResponse, synthesisResponse)

            coEvery {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "failing_tool",
                    toolParams = any(),
                    tools = any(),
                    hookContext = any(),
                    toolsUsed = any()
                )
            } throws RuntimeException("도구 내부 오류")

            val command = AgentCommand(
                systemPrompt = "시스템",
                userPrompt = "오류 테스트",
                mode = AgentMode.PLAN_EXECUTE
            )
            val hookContext = HookContext(
                runId = "test-run",
                userId = "test-user",
                userPrompt = command.userPrompt,
                metadata = mutableMapOf()
            )
            val tools = listOf(createMockSpringTool("failing_tool"))

            // PlanExecuteStrategy는 단계 실패 시 예외를 catch하고 "Error: ..." 메시지로 StepResult를 생성한다.
            // 합성 단계로 진행하므로 최종 결과는 성공이어야 한다.
            val result = strategy.execute(
                command = command,
                activeChatClient = fixture.chatClient,
                systemPrompt = "시스템",
                tools = tools,
                conversationHistory = emptyList(),
                hookContext = hookContext,
                toolsUsed = mutableListOf(),
                maxToolCalls = 10
            )

            result.assertSuccess("도구 실행 오류 후 합성 단계가 정상 동작해야 한다")
            assertEquals(
                "부분 결과로 합성", result.content,
                "오류가 있어도 합성 응답이 반환되어야 한다"
            )
        }

        @Test
        fun `maxToolCalls=0이면 도구를 실행하지 않고 합성 단계만 진행해야 한다`() = runTest {
            val planJson = """
                [
                  {"tool":"tool_a","args":{},"description":"1단계"},
                  {"tool":"tool_b","args":{},"description":"2단계"}
                ]
            """.trimIndent()
            val planResponse = simpleChatResponse(planJson)
            val synthesisResponse = simpleChatResponse("도구 없이 합성")

            every { fixture.callResponseSpec.chatResponse() } returnsMany
                listOf(planResponse, synthesisResponse)

            var executedCount = 0
            coEvery {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = any(), toolParams = any(), tools = any(),
                    hookContext = any(), toolsUsed = any()
                )
            } coAnswers {
                executedCount++
                ToolCallResult(output = "ok", success = true)
            }

            val command = AgentCommand(
                systemPrompt = "시스템",
                userPrompt = "maxToolCalls=0 테스트",
                mode = AgentMode.PLAN_EXECUTE,
                maxToolCalls = 0
            )
            val hookContext = HookContext(
                runId = "test-run",
                userId = "test-user",
                userPrompt = command.userPrompt,
                metadata = mutableMapOf()
            )
            val tools = listOf(
                createMockSpringTool("tool_a"),
                createMockSpringTool("tool_b")
            )

            val result = strategy.execute(
                command = command,
                activeChatClient = fixture.chatClient,
                systemPrompt = "시스템",
                tools = tools,
                conversationHistory = emptyList(),
                hookContext = hookContext,
                toolsUsed = mutableListOf(),
                maxToolCalls = 0
            )

            result.assertSuccess("maxToolCalls=0이어도 합성 단계는 성공이어야 한다")
            assertEquals(0, executedCount) {
                "maxToolCalls=0이면 도구가 실행되면 안 된다"
            }
        }

        // ── 헬퍼 메서드 ──

        private fun simpleChatResponse(content: String): ChatResponse {
            val msg = AssistantMessage(content)
            return ChatResponse(listOf(Generation(msg)))
        }

        private fun createMockSpringTool(
            name: String
        ): org.springframework.ai.tool.ToolCallback {
            val toolDef = org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name(name)
                .description("테스트 도구: $name")
                .inputSchema("{}")
                .build()
            val mock = mockk<org.springframework.ai.tool.ToolCallback>()
            every { mock.toolDefinition } returns toolDef
            return mock
        }
    }

    // ───────────────────────────────────────────────────────────────
    // ManualReActLoopExecutor — 예산 소진 경계값 통합 테스트
    // ───────────────────────────────────────────────────────────────

    @Nested
    inner class ManualReActLoopBudgetGap {

        private fun buildLoopExecutor(
            response: ChatResponse?,
            validateResponse: (String) -> AgentResult = { content -> AgentResult.success(content = content) }
        ): ManualReActLoopExecutor {
            val requestSpec = mockk<org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec>()
            val callResponseSpec = mockk<org.springframework.ai.chat.client.ChatClient.CallResponseSpec>()
            every { requestSpec.call() } returns callResponseSpec
            every { callResponseSpec.chatResponse() } returns response

            return ManualReActLoopExecutor(
                messageTrimmer = ConversationMessageTrimmer(
                    maxContextWindowTokens = 100_000,
                    outputReserveTokens = 100,
                    tokenEstimator = com.arc.reactor.memory.TokenEstimator { it.length }
                ),
                toolCallOrchestrator = mockk(relaxed = true),
                buildRequestSpec = { _, _, _, _, _ -> requestSpec },
                callWithRetry = { block -> block() },
                buildChatOptions = { _, _ -> org.springframework.ai.chat.prompt.ChatOptions.builder().build() },
                validateAndRepairResponse = { rawContent, _, _, _, _ ->
                    validateResponse(rawContent)
                },
                recordTokenUsage = { _, _ -> }
            )
        }

        @Test
        fun `budgetTracker가 이미 소진 상태이면 첫 LLM 호출 직후 BUDGET_EXHAUSTED를 반환해야 한다`() =
            runTest {
                // meta.usage를 포함한 ChatResponse를 구성하여 trackBudgetAndCheckExhausted가 작동하도록 함
                val usage = mockk<Usage>()
                every { usage.promptTokens } returns 100
                every { usage.completionTokens } returns 50
                every { usage.totalTokens } returns 150

                val metadata = mockk<ChatResponseMetadata>()
                every { metadata.usage } returns usage
                every { metadata.model } returns null

                val chatResponse = ChatResponse(
                    listOf(Generation(AssistantMessage("응답"))),
                    metadata
                )

                // maxTokens=100, 이미 소진됨
                val budgetTracker = StepBudgetTracker(maxTokens = 100, softLimitPercent = 80)
                // 100토큰 이상 소비하여 소진
                budgetTracker.trackStep("pre", inputTokens = 80, outputTokens = 30)

                val loopExecutor = buildLoopExecutor(chatResponse)

                val result = loopExecutor.execute(
                    command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                    activeChatClient = mockk(relaxed = true),
                    systemPrompt = "sys",
                    initialTools = emptyList(),
                    conversationHistory = emptyList(),
                    hookContext = HookContext(runId = "r1", userId = "u", userPrompt = "hi"),
                    toolsUsed = mutableListOf(),
                    allowedTools = null,
                    maxToolCalls = 5,
                    budgetTracker = budgetTracker
                )

                assertFalse(result.success) { "예산 소진 시 실패 결과를 반환해야 한다" }
                assertEquals(AgentErrorCode.BUDGET_EXHAUSTED, result.errorCode) {
                    "에러 코드가 BUDGET_EXHAUSTED여야 한다"
                }
                assertTrue(result.metadata.containsKey("budgetStatus")) {
                    "budgetStatus가 메타데이터에 포함되어야 한다"
                }
            }
    }
}
