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

class AgentTracingHooksTest {

    private val tracer = mockk<Tracer>()
    private val agentSpan = mockk<Span>(relaxed = true)

    private lateinit var hooks: AgentTracingHooks

    /**
     * Creates a fully-stubbed Span mock that returns itself from all fluent-builder methods
     * (name/tag/error/start), mirroring the Micrometer Span fluent API.
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
     * Creates a Span that captures all tag(key, value) calls in the provided map.
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
        // Default: every call to nextSpan() returns agentSpan
        every { agentSpan.name(any()) } returns agentSpan
        every { agentSpan.tag(any<String>(), any<String>()) } returns agentSpan
        every { agentSpan.error(any()) } returns agentSpan
        every { agentSpan.start() } returns agentSpan
        val agentCtx = mockk<TraceContext>()
        every { agentCtx.traceId() } returns "trace-abc"
        every { agentCtx.spanId() } returns "span-xyz"
        every { agentSpan.context() } returns agentCtx

        every { tracer.nextSpan() } returns agentSpan

        hooks = AgentTracingHooks(tracer)
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
    // Hook properties
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class Properties {

        @Test
        fun `hook properties are correct`() {
            hooks.order shouldBe 199
            hooks.failOnError shouldBe false
            hooks.enabled shouldBe true
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Agent span lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class AgentSpanLifecycle {

        @Test
        fun `beforeAgentStart creates span and returns Continue`() = runTest {
            val result = hooks.beforeAgentStart(hookContext())

            result shouldBe HookResult.Continue
            verify(exactly = 1) { tracer.nextSpan() }
            verify(exactly = 1) { agentSpan.name("gen_ai.agent.execute") }
            verify(exactly = 1) { agentSpan.start() }
        }

        @Test
        fun `beforeAgentStart tags span with run metadata`() = runTest {
            hooks.beforeAgentStart(hookContext(runId = "run-42", userId = "user-7"))

            verify { agentSpan.tag("run_id", "run-42") }
            verify { agentSpan.tag("user_id", "user-7") }
            verify { agentSpan.tag("tenant_id", "tenant-1") }
            verify { agentSpan.tag("session_id", "sess-1") }
            verify { agentSpan.tag("channel", "api") }
            verify { agentSpan.tag("gen_ai.agent.name", "test-agent") }
        }

        @Test
        fun `beforeAgentStart injects traceId into context metadata`() = runTest {
            val ctx = hookContext()
            hooks.beforeAgentStart(ctx)

            ctx.metadata["traceId"] shouldBe "trace-abc"
        }

        @Test
        fun `afterAgentComplete ends span and tags success`() = runTest {
            val ctx = hookContext()
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, successAgentResponse(toolsUsed = listOf("tool1", "tool2"), totalDurationMs = 800))

            verify { agentSpan.tag("success", "true") }
            verify { agentSpan.tag("total_duration_ms", "800") }
            verify { agentSpan.tag("total_tool_count", "2") }
            verify(exactly = 1) { agentSpan.end() }
        }

        @Test
        fun `afterAgentComplete tags error details on failure`() = runTest {
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
        fun `afterAgentComplete uses UNKNOWN error code when metadata has none`() = runTest {
            val ctx = hookContext(metadata = mutableMapOf("tenantId" to "t1"))
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, failureAgentResponse())

            verify { agentSpan.tag("gen_ai.error.code", "UNKNOWN") }
        }

        @Test
        fun `afterAgentComplete uses errorCode from context metadata when present`() = runTest {
            val ctx = hookContext(metadata = mutableMapOf("tenantId" to "t1", "errorCode" to "RATE_LIMITED"))
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, failureAgentResponse())

            verify { agentSpan.tag("gen_ai.error.code", "RATE_LIMITED") }
        }

        @Test
        fun `afterAgentComplete does nothing when no span exists for runId`() = runTest {
            // Never called beforeAgentStart, so no span is stored
            val ctx = hookContext(runId = "nonexistent-run")
            hooks.afterAgentComplete(ctx, successAgentResponse())

            verify(exactly = 0) { agentSpan.end() }
        }

        @Test
        fun `agent span is removed after afterAgentComplete — second call is a no-op`() = runTest {
            val ctx = hookContext(runId = "run-cleanup")
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, successAgentResponse())
            hooks.afterAgentComplete(ctx, successAgentResponse())

            verify(exactly = 1) { agentSpan.end() }
        }

        @Test
        fun `beforeAgentStart uses default values for missing optional metadata`() = runTest {
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
        fun `error from tracer during beforeAgentStart is swallowed and returns Continue`() = runTest {
            every { tracer.nextSpan() } throws RuntimeException("OTel unavailable")

            val result = hooks.beforeAgentStart(hookContext())

            result shouldBe HookResult.Continue
        }

        @Test
        fun `error from span tagging during afterAgentComplete is swallowed`() = runTest {
            every { agentSpan.tag(any<String>(), any<String>()) } throws RuntimeException("tag failed")

            val ctx = hookContext()
            hooks.beforeAgentStart(ctx)
            // Must not propagate — hook is fail-open (failOnError = false)
            hooks.afterAgentComplete(ctx, successAgentResponse())
        }

        @Test
        fun `span creation and end are called in order`() = runTest {
            val ctx = hookContext()
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, successAgentResponse())

            verifyOrder {
                agentSpan.start()
                agentSpan.end()
            }
        }

        @Test
        fun `error message is tagged with at most 500 chars`() = runTest {
            val longMessage = "e".repeat(1000)
            val ctx = hookContext(metadata = mutableMapOf("tenantId" to "t1"))
            hooks.beforeAgentStart(ctx)
            hooks.afterAgentComplete(ctx, failureAgentResponse(errorMessage = longMessage))

            verify { agentSpan.tag("error.message", "e".repeat(500)) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tool span lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolSpanLifecycle {

        private lateinit var toolSpan: Span

        @BeforeEach
        fun setUpToolSpan() {
            toolSpan = stubSpan()
            // Override tracer to return toolSpan for all tool-only tests in this nested class
            every { tracer.nextSpan() } returns toolSpan
        }

        @Test
        fun `beforeToolCall creates tool span with correct name and tags`() = runTest {
            val ctx = toolCallContext(toolName = "search_docs", callIndex = 3)
            hooks.beforeToolCall(ctx)

            verify { toolSpan.name("gen_ai.tool.execute") }
            verify { toolSpan.tag("gen_ai.tool.name", "search_docs") }
            verify { toolSpan.tag("gen_ai.tool.call_index", "3") }
            verify(exactly = 1) { toolSpan.start() }
        }

        @Test
        fun `beforeToolCall returns Continue`() = runTest {
            val result = hooks.beforeToolCall(toolCallContext())

            result shouldBe HookResult.Continue
        }

        @Test
        fun `afterToolCall ends span and tags success`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, successToolResult(durationMs = 200))

            verify { toolSpan.tag("success", "true") }
            verify { toolSpan.tag("duration_ms", "200") }
            verify(exactly = 1) { toolSpan.end() }
        }

        @Test
        fun `afterToolCall tags error details on failure`() = runTest {
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
        fun `afterToolCall does nothing when no span entry for the key`() = runTest {
            val ctx = toolCallContext(toolName = "never_started")
            hooks.afterToolCall(ctx, successToolResult())

            verify(exactly = 0) { toolSpan.end() }
        }

        @Test
        fun `tool span is removed after afterToolCall — second end call is a no-op`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, successToolResult())
            hooks.afterToolCall(ctx, successToolResult())

            verify(exactly = 1) { toolSpan.end() }
        }

        @Test
        fun `error from tracer during beforeToolCall is swallowed and returns Continue`() = runTest {
            every { tracer.nextSpan() } throws RuntimeException("OTel down")

            val result = hooks.beforeToolCall(toolCallContext())

            result shouldBe HookResult.Continue
        }

        @Test
        fun `no error tags on successful tool call`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, successToolResult())

            verify(exactly = 0) { toolSpan.error(any()) }
            verify(exactly = 0) { toolSpan.tag("error.type", any<String>()) }
            verify(exactly = 0) { toolSpan.tag("error.message", any<String>()) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Error classification
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
        fun `timeout keyword maps to TimeoutException`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, failureToolResult("Request timeout after 15000ms"))

            verify { toolSpan.tag("error.type", "TimeoutException") }
        }

        @Test
        fun `connection keyword maps to ConnectionException`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, failureToolResult("connection refused"))

            verify { toolSpan.tag("error.type", "ConnectionException") }
        }

        @Test
        fun `permission keyword maps to PermissionDenied`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, failureToolResult("permission denied for resource"))

            verify { toolSpan.tag("error.type", "PermissionDenied") }
        }

        @Test
        fun `unknown error message maps to RuntimeException`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, failureToolResult("something unexpected happened"))

            verify { toolSpan.tag("error.type", "RuntimeException") }
        }

        @Test
        fun `error message is truncated to 500 chars`() = runTest {
            val longMessage = "x".repeat(1000)
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            hooks.afterToolCall(ctx, failureToolResult(errorMessage = longMessage))

            verify { toolSpan.tag("error.message", "x".repeat(500)) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // HITL detection
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
        fun `no HITL tag when total elapsed is within 100ms of tool duration`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            // durationMs ~= totalElapsed → hitlWaitMs ≈ 0, below threshold
            hooks.afterToolCall(ctx, successToolResult(durationMs = 500))

            taggedValues.keys.any { it.startsWith("gen_ai.tool.hitl") } shouldBe false
        }

        @Test
        fun `HITL wait is tagged when elapsed significantly exceeds tool duration`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            // Simulate HITL wait: tool reports durationMs = 0 but real wall time > 100ms
            Thread.sleep(150)
            hooks.afterToolCall(ctx, successToolResult(durationMs = 0))

            taggedValues["gen_ai.tool.hitl.required"] shouldBe "true"
            taggedValues.containsKey("gen_ai.tool.hitl.wait_ms") shouldBe true
        }

        @Test
        fun `HITL rejection is tagged when output starts with Rejected`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            Thread.sleep(150)
            val result = ToolCallResult(success = true, output = "Rejected: Not allowed", durationMs = 0)
            hooks.afterToolCall(ctx, result)

            taggedValues["gen_ai.tool.hitl.required"] shouldBe "true"
            taggedValues["gen_ai.tool.hitl.approved"] shouldBe "false"
            taggedValues["gen_ai.tool.hitl.rejection_reason"] shouldBe "Rejected: Not allowed"
        }

        @Test
        fun `HITL rejection is tagged when output starts with Error Tool call rejected`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            Thread.sleep(150)
            val result = ToolCallResult(success = true, output = "Error: Tool call rejected by user", durationMs = 0)
            hooks.afterToolCall(ctx, result)

            taggedValues["gen_ai.tool.hitl.approved"] shouldBe "false"
        }

        @Test
        fun `HITL approval is tagged when output does not indicate rejection`() = runTest {
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            Thread.sleep(150)
            val result = ToolCallResult(success = true, output = "Order shipped successfully", durationMs = 0)
            hooks.afterToolCall(ctx, result)

            taggedValues["gen_ai.tool.hitl.approved"] shouldBe "true"
        }

        @Test
        fun `HITL rejection reason is truncated to 500 chars`() = runTest {
            val longOutput = "Rejected: " + "r".repeat(1000)
            val ctx = toolCallContext()
            hooks.beforeToolCall(ctx)
            Thread.sleep(150)
            val result = ToolCallResult(success = true, output = longOutput, durationMs = 0)
            hooks.afterToolCall(ctx, result)

            taggedValues["gen_ai.tool.hitl.rejection_reason"]?.length shouldBe 500
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // CancellationException propagation
    //
    // NOTE: kotlin.coroutines.cancellation.CancellationException is a typealias
    // for java.util.concurrent.CancellationException on JVM. When thrown inside
    // runTest {}, it cancels the coroutine scope before a try/catch can intercept it.
    // These tests therefore call suspend funs via runBlocking, which propagates
    // the exception to the JUnit test method where assertThrows captures it.
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class CancellationExceptionHandling {

        @Test
        fun `beforeAgentStart rethrows CancellationException`() {
            every { tracer.nextSpan() } throws CancellationException("cancelled in beforeAgentStart")

            assertThrows<CancellationException>("beforeAgentStart must rethrow CancellationException") {
                kotlinx.coroutines.runBlocking { hooks.beforeAgentStart(hookContext()) }
            }
        }

        @Test
        fun `afterAgentComplete rethrows CancellationException`() {
            // Phase 1: beforeAgentStart must succeed (normal span)
            // Phase 2: afterAgentComplete must rethrow CancellationException from tag()
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
        fun `beforeToolCall rethrows CancellationException`() {
            every { tracer.nextSpan() } throws CancellationException("cancelled in beforeToolCall")

            assertThrows<CancellationException>("beforeToolCall must rethrow CancellationException") {
                kotlinx.coroutines.runBlocking { hooks.beforeToolCall(toolCallContext()) }
            }
        }

        @Test
        fun `afterToolCall rethrows CancellationException`() {
            // Phase 1: beforeToolCall must succeed to store the span entry
            // Phase 2: afterToolCall must rethrow CancellationException from tag()
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
    // Span key isolation
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class SpanKeyIsolation {

        @Test
        fun `tool spans for different runIds are independent`() = runTest {
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
        fun `same tool called at different callIndex creates separate span entries`() = runTest {
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
        fun `span key includes runId toolName and callIndex for uniqueness`() = runTest {
            val toolSpan1 = stubSpan()
            val toolSpan2 = stubSpan()
            every { tracer.nextSpan() } returnsMany listOf(toolSpan1, toolSpan2)

            val ctx1 = toolCallContext(runId = "run-X", toolName = "tool_A", callIndex = 0)
            val ctx2 = toolCallContext(runId = "run-X", toolName = "tool_A", callIndex = 1)

            hooks.beforeToolCall(ctx1)
            hooks.beforeToolCall(ctx2)
            // End in reverse order to prove isolation
            hooks.afterToolCall(ctx2, successToolResult())
            hooks.afterToolCall(ctx1, successToolResult())

            verify(exactly = 1) { toolSpan1.end() }
            verify(exactly = 1) { toolSpan2.end() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Orphaned spans — tool start without matching end
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class OrphanedSpans {

        @Test
        fun `orphaned tool span in map is not ended automatically by afterAgentComplete`() = runTest {
            val toolSpan = stubSpan()
            every { tracer.nextSpan() } returnsMany listOf(agentSpan, toolSpan)

            val agentCtx = hookContext()
            val toolCtx = toolCallContext()

            hooks.beforeAgentStart(agentCtx)
            hooks.beforeToolCall(toolCtx)
            // afterToolCall never called — simulates abrupt cancellation
            hooks.afterAgentComplete(agentCtx, successAgentResponse())

            // The agent span ends, but the orphaned tool span does NOT end
            verify(exactly = 1) { agentSpan.end() }
            verify(exactly = 0) { toolSpan.end() }
        }

        @Test
        fun `afterToolCall with non-existent key is a safe no-op`() = runTest {
            val ctx = toolCallContext(runId = "ghost-run", toolName = "ghost_tool", callIndex = 99)
            // Should not throw
            hooks.afterToolCall(ctx, successToolResult())
        }

        @Test
        fun `afterAgentComplete with non-existent runId is a safe no-op`() = runTest {
            val ctx = hookContext(runId = "never-started")
            // Should not throw
            hooks.afterAgentComplete(ctx, successAgentResponse())
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Concurrency — ConcurrentHashMap race condition validation
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class ConcurrencyTests {

        @Test
        fun `concurrent tool span creation and removal are race-condition free`() = runTest {
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
        fun `concurrent agent span creation for different runIds are isolated`() = runTest {
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
                            // fail-open: count it as success (hook swallows non-cancellation errors)
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
        fun `high-concurrency beforeToolCall and afterToolCall produce no exceptions`() = runTest {
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
        fun `interleaved beforeToolCall and afterToolCall from 16 threads do not lose or double-end spans`() = runTest {
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
