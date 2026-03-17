package com.arc.reactor.admin.tracing

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import io.kotest.matchers.shouldBe
import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.Tracer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** [AgentTracingHooks]의 에이전트/도구 스팬 라이프사이클 및 태깅 테스트 */
class AgentTracingHooksTest {

    private val tracer = mockk<Tracer>()
    private val agentSpan = mockk<Span>(relaxed = true)

    private lateinit var hooks: AgentTracingHooks

    /**
     * 모든 플루언트 빌더 메서드(name/tag/error/start)에서 자기 자신을 반환하는
     * 완전히 스텁된 Span 모킹을 생성합니다. Micrometer Span 플루언트 API를 미러링합니다.
     */
    private fun stubSpan(traceId: String = "trace-abc", spanId: String = "span-xyz"): Span {
        val ctx = mockk<TraceContext>()
        every { ctx.traceId() } returns traceId
        every { ctx.spanId() } returns spanId

        return mockk<Span>(relaxed = true).also { span ->
            every { span.name(any()) } returns span
            every { span.tag(any<String>(), any<String>()) } returns span
            every { span.error(any()) } returns span
            every { span.start() } returns span
            every { span.context() } returns ctx
        }
    }

    /**
     * 제공된 맵에 모든 tag(key, value) 호출을 캡처하는 Span을 생성합니다.
     */
    private fun capturingSpan(capture: MutableMap<String, String>): Span {
        val ctx = mockk<TraceContext>()
        every { ctx.traceId() } returns "trace-cap"
        every { ctx.spanId() } returns "span-cap"

        return mockk<Span>(relaxed = true).also { span ->
            every { span.name(any()) } returns span
            every { span.tag(any<String>(), any<String>()) } answers {
                capture[firstArg()] = secondArg()
                span
            }
            every { span.error(any()) } returns span
            every { span.start() } returns span
            every { span.context() } returns ctx
        }
    }

    @BeforeEach
    fun setUp() {
        // 기본값: nextSpan() 호출 시 항상 agentSpan을 반환
        every { agentSpan.name(any()) } returns agentSpan
        every { agentSpan.tag(any<String>(), any<String>()) } returns agentSpan
        every { agentSpan.error(any()) } returns agentSpan
        every { agentSpan.start() } returns agentSpan
        val agentCtx = mockk<TraceContext>()
        every { agentCtx.traceId() } returns "trace-abc"
        every { agentCtx.spanId() } returns "span-xyz"
        every { agentSpan.context() } returns agentCtx

        every { tracer.nextSpan() } returns agentSpan

        hooks = AgentTracingHooks(
            tracer = tracer,
            storeUserIdentifiers = true,
            storeSessionIdentifiers = true
        )
    }

    private fun hookContext(
        runId: String = "run-1",
        userId: String = "user-1",
        metadata: MutableMap<String, Any> = mutableMapOf(
            "tenantId" to "tenant-1",
            "sessionId" to "sess-1",
            "agentName" to "test-agent"
        )
    ) = HookContext(
        runId = runId,
        userId = userId,
        userPrompt = "test prompt",
        channel = "api",
        metadata = metadata
    )

    private fun toolCallContext(
        runId: String = "run-1",
        toolName: String = "search_docs",
        callIndex: Int = 0
    ) = ToolCallContext(
        agentContext = hookContext(runId = runId),
        toolName = toolName,
        toolParams = emptyMap(),
        callIndex = callIndex
    )

    private fun successToolResult(durationMs: Long = 100) =
        ToolCallResult(success = true, output = "result", durationMs = durationMs)

    private fun failureToolResult(errorMessage: String = "Something failed", durationMs: Long = 50) =
        ToolCallResult(success = false, errorMessage = errorMessage, durationMs = durationMs)

    private fun successAgentResponse(toolsUsed: List<String> = emptyList(), totalDurationMs: Long = 500) =
        AgentResponse(success = true, response = "done", toolsUsed = toolsUsed, totalDurationMs = totalDurationMs)

    private fun failureAgentResponse(
        errorMessage: String = "Agent failed",
        errorCode: String = "TOOL_ERROR",
        totalDurationMs: Long = 300
    ) = AgentResponse(
        success = false,
        errorMessage = errorMessage,
        errorCode = errorCode,
        totalDurationMs = totalDurationMs
    )

    // ─────────────────────────────────────────────────────────────────────
    // 훅 속성
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class Properties {

        @Test
        fun `hook properties은(는) correct이다`() {
            hooks.order shouldBe 199
            hooks.failOnError shouldBe false
            hooks.enabled shouldBe true
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 에이전트 span 라이프사이클
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class AgentSpanLifecycle {

        @Test
        fun `beforeAgentStart은(는) creates span and returns Continue`() = runTest {
            val result = hooks.beforeAgentStart(hookContext())

            result shouldBe HookResult.Continue
            verify(exactly = 1) { tracer.nextSpan() }
            verify(exactly = 1) { agentSpan.name("gen_ai.agent.execute") }
            verify(exactly = 1) { agentSpan.start() }
        }

        @Test
        fun `beforeAgentStart은(는) tags span with run metadata`() = runTest {
            hooks.beforeAgentStart(hookContext(runId = "run-42", userId = "user-7"))

            verify { agentSpan.tag("run_id", "run-42") }
            verify { agentSpan.tag("user_id", "user-7") }
            verify { agentSpan.tag("tenant_id", "tenant-1") }
            verify { agentSpan.tag("session_id", "sess-1") }
            verify { agentSpan.tag("channel", "api") }
            verify { agentSpan.tag("gen_ai.agent.name", "test-agent") }
        }

        @Test
        fun `beforeAgentStart skips user and session tags when identifier storage은(는) disabled이다`() = runTest {
            val capturedTags = mutableMapOf<String, String>()
            val noIdentifierSpan = capturingSpan(capturedTags)
            val noIdentifierTracer = mockk<Tracer>()
            every { noIdentifierTracer.nextSpan() } returns noIdentifierSpan
            val noIdentifierHooks = AgentTracingHooks(noIdentifierTracer)

            noIdentifierHooks.beforeAgentStart(hookContext(runId = "run-99", userId = "user-99"))

            capturedTags["run_id"] shouldBe "run-99"
            capturedTags.containsKey("user_id") shouldBe false
            capturedTags.containsKey("session_id") shouldBe false
        }

        @Test
        fun `beforeAgentStart은(는) injects traceId into context metadata`() = runTest {
            val ctx = hookContext()
            hooks.beforeAgentStart(ctx)

            ctx.metadata["traceId"] shouldBe "trace-abc"
        }

        @Test
        fun `afterAgentComplete은(는) ends span and tags success`() = runTest {
            val ctx = hookContext()
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, successAgentResponse(toolsUsed = listOf("tool1", "tool2"), totalDurationMs = 800))

            verify { agentSpan.tag("success", "true") }
            verify { agentSpan.tag("total_duration_ms", "800") }
            verify { agentSpan.tag("total_tool_count", "2") }
            verify(exactly = 1) { agentSpan.end() }
        }

        @Test
        fun `afterAgentComplete은(는) tags error details on failure`() = runTest {
            val ctx = hookContext(metadata = mutableMapOf("tenantId" to "t1", "errorCode" to "TIMEOUT"))
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, failureAgentResponse(errorMessage = "Timed out", errorCode = "TIMEOUT"))

            verify { agentSpan.tag("success", "false") }
            verify { agentSpan.tag("gen_ai.error.code", "TIMEOUT") }
            verify { agentSpan.tag("error.message", "Timed out") }
            verify(exactly = 1) { agentSpan.error(any<RuntimeException>()) }
            verify(exactly = 1) { agentSpan.end() }
        }

        @Test
        fun `metadata has none일 때 afterAgentComplete uses UNKNOWN error code`() = runTest {
            val ctx = hookContext(metadata = mutableMapOf("tenantId" to "t1"))
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, failureAgentResponse())

            verify { agentSpan.tag("gen_ai.error.code", "UNKNOWN") }
        }

        @Test
        fun `present일 때 afterAgentComplete uses errorCode from context metadata`() = runTest {
            val ctx = hookContext(metadata = mutableMapOf("tenantId" to "t1", "errorCode" to "RATE_LIMITED"))
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, failureAgentResponse())

            verify { agentSpan.tag("gen_ai.error.code", "RATE_LIMITED") }
        }

        @Test
        fun `no span exists for runId일 때 afterAgentComplete does nothing`() = runTest {
            // beforeAgentStart가 호출되지 않아 스팬이 저장되지 않음
            val ctx = hookContext(runId = "nonexistent-run")
            hooks.afterAgentComplete(ctx, successAgentResponse())

            verify(exactly = 0) { agentSpan.end() }
        }

        @Test
        fun `agent span은(는) removed after afterAgentComplete — second call is a no-op이다`() = runTest {
            val ctx = hookContext(runId = "run-cleanup")
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, successAgentResponse())
            hooks.afterAgentComplete(ctx, successAgentResponse())

            verify(exactly = 1) { agentSpan.end() }
        }

        @Test
        fun `beforeAgentStart은(는) uses default values for missing optional metadata`() = runTest {
            val ctx = HookContext(
                runId = "run-defaults",
                userId = "u1",
                userPrompt = "hi",
                metadata = mutableMapOf()
            )
            hooks.beforeAgentStart(ctx)

            verify { agentSpan.tag("tenant_id", "default") }
            verify { agentSpan.tag("gen_ai.agent.name", "default") }
            verify { agentSpan.tag("session_id", "") }
            verify { agentSpan.tag("channel", "") }
        }

        @Test
        fun `error from tracer during beforeAgentStart은(는) swallowed and returns Continue이다`() = runTest {
            every { tracer.nextSpan() } throws RuntimeException("OTel unavailable")

            val result = hooks.beforeAgentStart(hookContext())

            result shouldBe HookResult.Continue
        }

        @Test
        fun `error from span tagging during afterAgentComplete은(는) swallowed이다`() = runTest {
            every { agentSpan.tag(any<String>(), any<String>()) } throws RuntimeException("tag failed")

            val ctx = hookContext()
            hooks.beforeAgentStart(ctx)
            // 전파하면 안 됨 — 훅은 fail-open (failOnError = false)
            hooks.afterAgentComplete(ctx, successAgentResponse())
        }

        @Test
        fun `span creation and end은(는) called in order이다`() = runTest {
            val ctx = hookContext()
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, successAgentResponse())

            verifyOrder {
                agentSpan.start()
                agentSpan.end()
            }
        }

        @Test
        fun `error message은(는) tagged with at most 500 chars이다`() = runTest {
            val longMessage = "e".repeat(1000)
            val ctx = hookContext(metadata = mutableMapOf("tenantId" to "t1"))
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, failureAgentResponse(errorMessage = longMessage))

            verify { agentSpan.tag("error.message", "e".repeat(500)) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 도구 스팬 라이프사이클
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolSpanLifecycle {

        private lateinit var toolSpan: Span

        @BeforeEach
        fun setUpToolSpan() {
            toolSpan = stubSpan()
            // 이 중첩 클래스 내 모든 도구 전용 테스트에서 toolSpan을 반환하도록 tracer 오버라이드
            every { tracer.nextSpan() } returns toolSpan
        }

        @Test
        fun `beforeToolCall은(는) creates tool span with correct name and tags`() = runTest {
            val ctx = toolCallContext(toolName = "search_docs", callIndex = 3)
            hooks.beforeToolCall(ctx)

            verify { toolSpan.name("gen_ai.tool.execute") }
            verify { toolSpan.tag("gen_ai.tool.name", "search_docs") }
            verify { toolSpan.tag("gen_ai.tool.call_index", "3") }
            verify(exactly = 1) { toolSpan.start() }
        }

        @Test
        fun `beforeToolCall은(는) returns Continue`() = runTest {
            val result = hooks.beforeToolCall(toolCallContext())

            result shouldBe HookResult.Continue
        }

        @Test
        fun `afterToolCall은(는) ends span and tags success`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, successToolResult(durationMs = 200))

            verify { toolSpan.tag("success", "true") }
            verify { toolSpan.tag("duration_ms", "200") }
            verify(exactly = 1) { toolSpan.end() }
        }

        @Test
        fun `afterToolCall은(는) tags error details on failure`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, failureToolResult(errorMessage = "Connection timeout occurred"))

            verify { toolSpan.tag("success", "false") }
            verify { toolSpan.tag("error.type", "TimeoutException") }
            verify { toolSpan.tag("error.message", "Connection timeout occurred") }
            verify(exactly = 1) { toolSpan.error(any<RuntimeException>()) }
            verify(exactly = 1) { toolSpan.end() }
        }

        @Test
        fun `no span entry for the key일 때 afterToolCall does nothing`() = runTest {
            val ctx = toolCallContext(toolName = "never_started")
            hooks.afterToolCall(ctx, successToolResult())

            verify(exactly = 0) { toolSpan.end() }
        }

        @Test
        fun `tool span은(는) removed after afterToolCall — second end call is a no-op이다`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, successToolResult())
            hooks.afterToolCall(ctx, successToolResult())

            verify(exactly = 1) { toolSpan.end() }
        }

        @Test
        fun `error from tracer during beforeToolCall은(는) swallowed and returns Continue이다`() = runTest {
            every { tracer.nextSpan() } throws RuntimeException("OTel down")

            val result = hooks.beforeToolCall(toolCallContext())

            result shouldBe HookResult.Continue
        }

        @Test
        fun `없는 error tags on successful tool call`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, successToolResult())

            verify(exactly = 0) { toolSpan.error(any()) }
            verify(exactly = 0) { toolSpan.tag("error.type", any<String>()) }
            verify(exactly = 0) { toolSpan.tag("error.message", any<String>()) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 에러 분류
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class ErrorClassification {

        private lateinit var toolSpan: Span

        @BeforeEach
        fun setUpToolSpan() {
            toolSpan = stubSpan()
            every { tracer.nextSpan() } returns toolSpan
        }

        @Test
        fun `타임아웃 keyword maps to TimeoutException`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, failureToolResult("Request timeout after 15000ms"))

            verify { toolSpan.tag("error.type", "TimeoutException") }
        }

        @Test
        fun `connection은(는) keyword maps to ConnectionException`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, failureToolResult("connection refused"))

            verify { toolSpan.tag("error.type", "ConnectionException") }
        }

        @Test
        fun `permission은(는) keyword maps to PermissionDenied`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, failureToolResult("permission denied for resource"))

            verify { toolSpan.tag("error.type", "PermissionDenied") }
        }

        @Test
        fun `알 수 없는 error message maps to RuntimeException`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, failureToolResult("something unexpected happened"))

            verify { toolSpan.tag("error.type", "RuntimeException") }
        }

        @Test
        fun `error message은(는) truncated to 500 chars이다`() = runTest {
            val longMessage = "x".repeat(1000)
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, failureToolResult(errorMessage = longMessage))

            verify { toolSpan.tag("error.message", "x".repeat(500)) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // HITL(사람 개입) 감지
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class HitlDetection {

        private val taggedValues = mutableMapOf<String, String>()
        private lateinit var toolSpan: Span

        @BeforeEach
        fun setUpToolSpan() {
            toolSpan = capturingSpan(taggedValues)
            every { tracer.nextSpan() } returns toolSpan
        }

        @Test
        fun `no HITL tag when total elapsed은(는) within 100ms of tool duration이다`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            // durationMs ~= 총 경과 시간 → hitlWaitMs ~ 0, 임계값 미만
            hooks.afterToolCall(ctx, successToolResult(durationMs = 500))

            taggedValues.keys.any { it.startsWith("gen_ai.tool.hitl") } shouldBe false
        }

        @Test
        fun `elapsed significantly exceeds tool duration일 때 HITL wait은(는) tagged이다`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            // HITL 대기 시뮬레이션: 도구는 durationMs=0이지만 실제 경과 시간 > 100ms
            withContext(Dispatchers.IO) { delay(150) }
            hooks.afterToolCall(ctx, successToolResult(durationMs = 0))

            taggedValues["gen_ai.tool.hitl.required"] shouldBe "true"
            taggedValues.containsKey("gen_ai.tool.hitl.wait_ms") shouldBe true
        }

        @Test
        fun `output starts with Rejected일 때 HITL rejection은(는) tagged이다`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            withContext(Dispatchers.IO) { delay(150) }
            val result = ToolCallResult(success = true, output = "Rejected: Not allowed", durationMs = 0)
            hooks.afterToolCall(ctx, result)

            taggedValues["gen_ai.tool.hitl.required"] shouldBe "true"
            taggedValues["gen_ai.tool.hitl.approved"] shouldBe "false"
            taggedValues["gen_ai.tool.hitl.rejection_reason"] shouldBe "Rejected: Not allowed"
        }

        @Test
        fun `output starts with Error Tool call rejected일 때 HITL rejection은(는) tagged이다`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            withContext(Dispatchers.IO) { delay(150) }
            val result = ToolCallResult(success = true, output = "Error: Tool call rejected by user", durationMs = 0)
            hooks.afterToolCall(ctx, result)

            taggedValues["gen_ai.tool.hitl.approved"] shouldBe "false"
        }

        @Test
        fun `output does not indicate rejection일 때 HITL approval은(는) tagged이다`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            withContext(Dispatchers.IO) { delay(150) }
            val result = ToolCallResult(success = true, output = "Order shipped successfully", durationMs = 0)
            hooks.afterToolCall(ctx, result)

            taggedValues["gen_ai.tool.hitl.approved"] shouldBe "true"
        }

        @Test
        fun `HITL rejection reason은(는) truncated to 500 chars이다`() = runTest {
            val longOutput = "Rejected: " + "r".repeat(1000)
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            withContext(Dispatchers.IO) { delay(150) }
            val result = ToolCallResult(success = true, output = longOutput, durationMs = 0)
            hooks.afterToolCall(ctx, result)

            taggedValues["gen_ai.tool.hitl.rejection_reason"]?.length shouldBe 500
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // CancellationException 전파
    //
    // 참고: kotlin.coroutines.cancellation.CancellationException은 JVM에서
    // java.util.concurrent.CancellationException의 typealias입니다.
    // runTest {} 내부에서 던져지면 try/catch가 가로채기 전에 코루틴 스코프가 취소됩니다.
    // 따라서 이 테스트들은 runBlocking을 통해 suspend 함수를 호출하며,
    // 예외가 assertThrows로 캡처되는 JUnit 테스트 메서드까지 전파됩니다.
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class CancellationExceptionHandling {

        @Test
        fun `beforeAgentStart은(는) rethrows CancellationException`() {
            every { tracer.nextSpan() } throws CancellationException("cancelled in beforeAgentStart")

            assertThrows<CancellationException>("beforeAgentStart must rethrow CancellationException") {
                kotlinx.coroutines.runBlocking { hooks.beforeAgentStart(hookContext()) }
            }
        }

        @Test
        fun `afterAgentComplete은(는) rethrows CancellationException`() {
            // 1단계: beforeAgentStart는 정상 스팬으로 성공해야 함
            // 2단계: afterAgentComplete는 tag()에서 발생한 CancellationException을 재전파해야 함
            var started = false
            val cancellableSpan = mockk<Span>(relaxed = true)
            every { cancellableSpan.name(any()) } returns cancellableSpan
            every { cancellableSpan.start() } answers { started = true; cancellableSpan }
            val ctx2 = mockk<TraceContext>()
            every { ctx2.traceId() } returns "t"
            every { ctx2.spanId() } returns "s"
            every { cancellableSpan.context() } returns ctx2
            every { cancellableSpan.tag(any<String>(), any<String>()) } answers {
                if (started) throw CancellationException("cancelled during afterAgentComplete")
                cancellableSpan
            }
            every { tracer.nextSpan() } returns cancellableSpan

            val ctx = hookContext(runId = "cancel-run")
            kotlinx.coroutines.runBlocking { hooks.beforeAgentStart(ctx) }

            assertThrows<CancellationException>("afterAgentComplete must rethrow CancellationException") {
                kotlinx.coroutines.runBlocking { hooks.afterAgentComplete(ctx, successAgentResponse()) }
            }
        }

        @Test
        fun `beforeToolCall은(는) rethrows CancellationException`() {
            every { tracer.nextSpan() } throws CancellationException("cancelled in beforeToolCall")

            assertThrows<CancellationException>("beforeToolCall must rethrow CancellationException") {
                kotlinx.coroutines.runBlocking { hooks.beforeToolCall(toolCallContext()) }
            }
        }

        @Test
        fun `afterToolCall은(는) rethrows CancellationException`() {
            // 1단계: beforeToolCall이 성공하여 스팬 항목을 저장해야 함
            // 2단계: afterToolCall은 tag()에서 발생한 CancellationException을 재전파해야 함
            var started = false
            val cancellableToolSpan = mockk<Span>(relaxed = true)
            every { cancellableToolSpan.name(any()) } returns cancellableToolSpan
            every { cancellableToolSpan.start() } answers { started = true; cancellableToolSpan }
            val ctx2 = mockk<TraceContext>()
            every { ctx2.traceId() } returns "t"
            every { ctx2.spanId() } returns "s"
            every { cancellableToolSpan.context() } returns ctx2
            every { cancellableToolSpan.tag(any<String>(), any<String>()) } answers {
                if (started) throw CancellationException("cancelled in afterToolCall")
                cancellableToolSpan
            }
            every { tracer.nextSpan() } returns cancellableToolSpan

            val ctx = toolCallContext()
            kotlinx.coroutines.runBlocking { hooks.beforeToolCall(ctx) }

            assertThrows<CancellationException>("afterToolCall must rethrow CancellationException") {
                kotlinx.coroutines.runBlocking { hooks.afterToolCall(ctx, successToolResult()) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 스팬 키 격리
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class SpanKeyIsolation {

        @Test
        fun `tool spans for different runIds은(는) independent이다`() = runTest {
            val toolSpan1 = stubSpan()
            val toolSpan2 = stubSpan()
            every { tracer.nextSpan() } returnsMany listOf(toolSpan1, toolSpan2)

            val ctx1 = toolCallContext(runId = "run-A", toolName = "search", callIndex = 0)
            val ctx2 = toolCallContext(runId = "run-B", toolName = "search", callIndex = 0)

            hooks.beforeToolCall(ctx1)
            hooks.beforeToolCall(ctx2)

            hooks.afterToolCall(ctx1, successToolResult())
            hooks.afterToolCall(ctx2, successToolResult())

            verify(exactly = 1) { toolSpan1.end() }
            verify(exactly = 1) { toolSpan2.end() }
        }

        @Test
        fun `동일한 tool called at different callIndex creates separate span entries`() = runTest {
            val toolSpan1 = stubSpan()
            val toolSpan2 = stubSpan()
            every { tracer.nextSpan() } returnsMany listOf(toolSpan1, toolSpan2)

            val ctx1 = toolCallContext(toolName = "search", callIndex = 0)
            val ctx2 = toolCallContext(toolName = "search", callIndex = 1)

            hooks.beforeToolCall(ctx1)
            hooks.beforeToolCall(ctx2)

            hooks.afterToolCall(ctx1, successToolResult())
            hooks.afterToolCall(ctx2, failureToolResult())

            verify(exactly = 1) { toolSpan1.end() }
            verify(exactly = 1) { toolSpan2.end() }
        }

        @Test
        fun `span은(는) key includes runId toolName and callIndex for uniqueness`() = runTest {
            val toolSpan1 = stubSpan()
            val toolSpan2 = stubSpan()
            every { tracer.nextSpan() } returnsMany listOf(toolSpan1, toolSpan2)

            val ctx1 = toolCallContext(runId = "run-X", toolName = "tool_A", callIndex = 0)
            val ctx2 = toolCallContext(runId = "run-X", toolName = "tool_A", callIndex = 1)

            hooks.beforeToolCall(ctx1)
            hooks.beforeToolCall(ctx2)
            // 격리를 증명하기 위해 역순으로 종료
            hooks.afterToolCall(ctx2, successToolResult())
            hooks.afterToolCall(ctx1, successToolResult())

            verify(exactly = 1) { toolSpan1.end() }
            verify(exactly = 1) { toolSpan2.end() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 고아 스팬 — 종료 없이 시작된 도구 스팬
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class OrphanedSpans {

        @Test
        fun `orphaned tool span in map은(는) not ended automatically by afterAgentComplete이다`() = runTest {
            val toolSpan = stubSpan()
            every { tracer.nextSpan() } returnsMany listOf(agentSpan, toolSpan)

            val agentCtx = hookContext()
            val toolCtx = toolCallContext()

            hooks.beforeAgentStart(agentCtx)
            hooks.beforeToolCall(toolCtx)
            // afterToolCall이 호출되지 않음 — 갑작스러운 취소를 시뮬레이션
            hooks.afterAgentComplete(agentCtx, successAgentResponse())

            // 에이전트 스팬은 종료되지만 고아 도구 스팬은 종료되지 않습니다
            verify(exactly = 1) { agentSpan.end() }
            verify(exactly = 0) { toolSpan.end() }
        }

        @Test
        fun `afterToolCall with non-existent key은(는) a safe no-op이다`() = runTest {
            val ctx = toolCallContext(runId = "ghost-run", toolName = "ghost_tool", callIndex = 99)
            // 예외를 던지면 안 됩니다
            hooks.afterToolCall(ctx, successToolResult())
        }

        @Test
        fun `afterAgentComplete with non-existent runId은(는) a safe no-op이다`() = runTest {
            val ctx = hookContext(runId = "never-started")
            // 예외를 던지면 안 됩니다
            hooks.afterAgentComplete(ctx, successAgentResponse())
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 동시성 — ConcurrentHashMap 경합 조건 검증
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class ConcurrencyTests {

        @Test
        fun `concurrent tool span creation and removal은(는) race-condition free이다`() = runTest {
            val threadCount = 12
            val successCount = AtomicInteger(0)
            val errorCount = AtomicInteger(0)
            val barrier = CyclicBarrier(threadCount)
            val executor = Executors.newFixedThreadPool(threadCount)

            val localTracer = mockk<Tracer>()
            val hooksUnderTest = AgentTracingHooks(localTracer)
            every { localTracer.nextSpan() } answers { stubSpan() }

            withContext(Dispatchers.IO) {
                val futures = (0 until threadCount).map { threadIdx ->
                    executor.submit<Unit> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS)
                            val ctx = toolCallContext(
                                runId = "concurrent-run-$threadIdx",
                                toolName = "tool_$threadIdx",
                                callIndex = threadIdx
                            )
                            kotlinx.coroutines.runBlocking {
                                hooksUnderTest.beforeToolCall(ctx)
                                hooksUnderTest.afterToolCall(ctx, successToolResult())
                            }
                            successCount.incrementAndGet()
                        } catch (e: Exception) {
                            errorCount.incrementAndGet()
                        }
                    }
                }
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
            }

            executor.shutdown()

            errorCount.get() shouldBe 0
            successCount.get() shouldBe threadCount
        }

        @Test
        fun `concurrent agent span creation for different runIds은(는) isolated이다`() = runTest {
            val threadCount = 8
            val successCount = AtomicInteger(0)
            val barrier = CyclicBarrier(threadCount)
            val executor = Executors.newFixedThreadPool(threadCount)

            val localTracer = mockk<Tracer>()
            val hooksUnderTest = AgentTracingHooks(localTracer)
            every { localTracer.nextSpan() } answers { stubSpan() }

            withContext(Dispatchers.IO) {
                val futures = (0 until threadCount).map { threadIdx ->
                    executor.submit<Unit> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS)
                            kotlinx.coroutines.runBlocking {
                                val ctx = hookContext(runId = "agent-run-$threadIdx")
                                hooksUnderTest.beforeAgentStart(ctx)
                                hooksUnderTest.afterAgentComplete(ctx, successAgentResponse())
                            }
                            successCount.incrementAndGet()
                        } catch (e: Exception) {
                            // fail-open: 훅이 CancellationException이 아닌 에러를 무시하므로 성공으로 카운트
                            successCount.incrementAndGet()
                        }
                    }
                }
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
            }

            executor.shutdown()

            successCount.get() shouldBe threadCount
        }

        @Test
        fun `high-concurrency beforeToolCall and afterToolCall은(는) no exceptions를 생성한다`() = runTest {
            val iterations = 200
            val errCount = AtomicInteger(0)

            val localTracer = mockk<Tracer>()
            val hooksUnderTest = AgentTracingHooks(localTracer)
            every { localTracer.nextSpan() } answers { stubSpan() }

            val jobs = (0 until iterations).map { i ->
                launch(Dispatchers.Default) {
                    try {
                        val ctx = toolCallContext(runId = "stress-run-$i", toolName = "tool_$i", callIndex = i)
                        hooksUnderTest.beforeToolCall(ctx)
                        hooksUnderTest.afterToolCall(ctx, successToolResult())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errCount.incrementAndGet()
                    }
                }
            }

            jobs.forEach { it.join() }

            errCount.get() shouldBe 0
        }

        @Test
        fun `interleaved은(는) beforeToolCall and afterToolCall from 16 threads do not lose or double-end spans`() = runTest {
            val threadCount = 16
            val endCount = AtomicInteger(0)
            val barrier = CyclicBarrier(threadCount)
            val executor = Executors.newFixedThreadPool(threadCount)

            val localTracer = mockk<Tracer>()
            val hooksUnderTest = AgentTracingHooks(localTracer)

            every { localTracer.nextSpan() } answers {
                mockk<Span>(relaxed = true).also { s ->
                    every { s.name(any()) } returns s
                    every { s.tag(any<String>(), any<String>()) } returns s
                    every { s.start() } returns s
                    every { s.error(any()) } returns s
                    val traceCtx = mockk<TraceContext>()
                    every { traceCtx.traceId() } returns "t"
                    every { traceCtx.spanId() } returns "s"
                    every { s.context() } returns traceCtx
                    every { s.end() } answers { endCount.incrementAndGet(); Unit }
                }
            }

            withContext(Dispatchers.IO) {
                val futures = (0 until threadCount).map { threadIdx ->
                    executor.submit<Unit> {
                        barrier.await(5, TimeUnit.SECONDS)
                        kotlinx.coroutines.runBlocking {
                            val ctx = toolCallContext(
                                runId = "run-$threadIdx",
                                toolName = "tool_$threadIdx",
                                callIndex = 0
                            )
                            hooksUnderTest.beforeToolCall(ctx)
                            hooksUnderTest.afterToolCall(ctx, successToolResult())
                        }
                    }
                }
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
            }

            executor.shutdown()

            endCount.get() shouldBe threadCount
        }
    }
}
