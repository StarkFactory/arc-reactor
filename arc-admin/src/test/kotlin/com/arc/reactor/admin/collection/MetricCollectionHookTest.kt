package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.AgentExecutionEvent
import com.arc.reactor.admin.model.GuardEvent
import com.arc.reactor.admin.model.McpHealthEvent
import com.arc.reactor.admin.model.SessionEvent
import com.arc.reactor.admin.model.ToolCallEvent
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MetricCollectionHookTest {

    private lateinit var ringBuffer: MetricRingBuffer
    private val healthMonitor = PipelineHealthMonitor()
    private lateinit var hook: MetricCollectionHook

    @BeforeEach
    fun setUp() {
        ringBuffer = MetricRingBuffer(64)
        hook = MetricCollectionHook(ringBuffer, healthMonitor)
    }

    private fun hookContext(
        runId: String = "run-1",
        userId: String = "user-1",
        metadata: MutableMap<String, Any> = mutableMapOf("tenantId" to "tenant-1")
    ) = HookContext(
        runId = runId,
        userId = userId,
        userPrompt = "test prompt",
        metadata = metadata
    )

    private fun agentResponse(
        success: Boolean = true,
        toolsUsed: List<String> = emptyList(),
        totalDurationMs: Long = 500,
        errorCode: String? = null
    ) = AgentResponse(
        success = success,
        response = "response",
        toolsUsed = toolsUsed,
        totalDurationMs = totalDurationMs,
        errorCode = errorCode
    )

    @Nested
    inner class Properties {

        @Test
        fun `hook properties은(는) correct이다`() {
            hook.order shouldBe 200
            hook.enabled shouldBe true
            hook.failOnError shouldBe false
        }
    }

    @Nested
    inner class AfterAgentComplete {

        @Test
        fun `execution event on success를 발행한다`() = runTest {
            val context = hookContext(metadata = mutableMapOf("tenantId" to "tenant-1", "sessionId" to "sess-1"))
            hook.afterAgentComplete(context, agentResponse(success = true, toolsUsed = listOf("tool1", "tool2")))

            val events = ringBuffer.drain(10)
            val event = events.filterIsInstance<AgentExecutionEvent>().single()
            event.tenantId shouldBe "tenant-1"
            event.runId shouldBe "run-1"
            event.userId shouldBe null
            event.sessionId shouldBe null
            event.success shouldBe true
            event.toolCount shouldBe 2
            event.durationMs shouldBe 500
        }

        @Test
        fun `execution event on failure with errorCode를 발행한다`() = runTest {
            val context = hookContext(metadata = mutableMapOf("tenantId" to "tenant-1"))
            hook.afterAgentComplete(context, agentResponse(success = false, errorCode = "TOOL_ERROR"))

            val events = ringBuffer.drain(10)
            val event = events.filterIsInstance<AgentExecutionEvent>().single()
            event.success shouldBe false
            event.errorCode shouldBe "TOOL_ERROR"
        }

        @Test
        fun `success is true even if response has errorCode일 때 errorCode은(는) null이다`() = runTest {
            val context = hookContext(metadata = mutableMapOf("tenantId" to "tenant-1"))
            hook.afterAgentComplete(context, agentResponse(success = true, errorCode = "TOOL_ERROR"))

            val events = ringBuffer.drain(10)
            val event = events.filterIsInstance<AgentExecutionEvent>().single()
            event.errorCode shouldBe null
        }

        @Test
        fun `latency breakdown from metadata를 추출한다`() = runTest {
            val context = hookContext(metadata = mutableMapOf(
                "tenantId" to "tenant-1",
                "llmDurationMs" to "300",
                "toolDurationMs" to "150",
                "guardDurationMs" to "20",
                "queueWaitMs" to "30"
            ))
            hook.afterAgentComplete(context, agentResponse())

            val event = ringBuffer.drain(10).filterIsInstance<AgentExecutionEvent>().single()
            event.llmDurationMs shouldBe 300
            event.toolDurationMs shouldBe 150
            event.guardDurationMs shouldBe 20
            event.queueWaitMs shouldBe 30
        }

        @Test
        fun `누락된 metadata values default to zero`() = runTest {
            hook.afterAgentComplete(hookContext(), agentResponse())

            val event = ringBuffer.drain(10).filterIsInstance<AgentExecutionEvent>().single()
            event.llmDurationMs shouldBe 0
            event.toolDurationMs shouldBe 0
            event.guardDurationMs shouldBe 0
            event.queueWaitMs shouldBe 0
            event.sessionId shouldBe null
            event.personaId shouldBe null
            event.intentCategory shouldBe null
        }

        @Test
        fun `records drop when buffer은(는) full이다`() = runTest {
            // 최소 용량은 64입니다 (MetricRingBuffer가 최소 64로 강제함)
            val tinyBuffer = MetricRingBuffer(64)
            val tinyHook = MetricCollectionHook(tinyBuffer, healthMonitor)

            // buffer to capacity를 채웁니다
            repeat(64) { tinyBuffer.publish(AgentExecutionEvent(tenantId = "t", runId = "r-$it", success = true)) }

            val dropsBefore = healthMonitor.droppedTotal.get()
            tinyHook.afterAgentComplete(hookContext(), agentResponse())
            healthMonitor.droppedTotal.get() shouldBe dropsBefore + 1
        }

        @Test
        fun `metadata has no tenantId일 때 default tenantId를 사용한다`() = runTest {
            hook.afterAgentComplete(hookContext(metadata = mutableMapOf()), agentResponse())

            val event = ringBuffer.drain(10).filterIsInstance<AgentExecutionEvent>().single()
            event.tenantId shouldBe "default"
        }
    }

    @Nested
    inner class AfterToolCall {

        private fun toolCallContext(
            toolName: String = "check_order",
            callIndex: Int = 0,
            metadata: MutableMap<String, Any> = mutableMapOf("tenantId" to "tenant-1")
        ) = ToolCallContext(
            agentContext = hookContext(metadata = metadata),
            toolName = toolName,
            toolParams = emptyMap(),
            callIndex = callIndex
        )

        private fun toolCallResult(
            success: Boolean = true,
            durationMs: Long = 100,
            errorMessage: String? = null
        ) = ToolCallResult(
            success = success,
            output = "result",
            errorMessage = errorMessage,
            durationMs = durationMs
        )

        @Test
        fun `tool call event on success를 발행한다`() = runTest {
            hook.afterToolCall(toolCallContext(), toolCallResult())

            val events = ringBuffer.drain(10)
            events.size shouldBe 1
            val event = events[0].shouldBeInstanceOf<ToolCallEvent>()
            event.tenantId shouldBe "tenant-1"
            event.runId shouldBe "run-1"
            event.toolName shouldBe "check_order"
            event.callIndex shouldBe 0
            event.success shouldBe true
            event.durationMs shouldBe 100
            event.errorClass shouldBe null
            event.errorMessage shouldBe null
        }

        @Test
        fun `timeout error를 분류한다`() = runTest {
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = "Connection timeout after 15000ms")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.success shouldBe false
            event.errorClass shouldBe "timeout"
        }

        @Test
        fun `connection error를 분류한다`() = runTest {
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = "Connection refused to host")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.errorClass shouldBe "connection_error"
        }

        @Test
        fun `permission denied error를 분류한다`() = runTest {
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = "Permission denied for resource X")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.errorClass shouldBe "permission_denied"
        }

        @Test
        fun `not found error를 분류한다`() = runTest {
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = "Resource not found")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.errorClass shouldBe "not_found"
        }

        @Test
        fun `unknown error를 분류한다`() = runTest {
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = "Something totally unexpected")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.errorClass shouldBe "unknown"
        }

        @Test
        fun `error message to 500 chars를 잘라낸다`() = runTest {
            val longMessage = "x".repeat(1000)
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = longMessage)
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.errorMessage!!.length shouldBe 500
        }

        @Test
        fun `tool source from metadata를 해결한다`() = runTest {
            val meta = mutableMapOf<String, Any>(
                "tenantId" to "tenant-1",
                "toolSource_check_order" to "mcp",
                "mcpServer_check_order" to "payment-server"
            )
            hook.afterToolCall(toolCallContext(metadata = meta), toolCallResult())

            val event = ringBuffer.drain(10).filterIsInstance<ToolCallEvent>().single()
            event.toolSource shouldBe "mcp"
            event.mcpServerName shouldBe "payment-server"
        }

        @Test
        fun `tool source to local를 기본값으로 한다`() = runTest {
            hook.afterToolCall(toolCallContext(), toolCallResult())

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.toolSource shouldBe "local"
            event.mcpServerName shouldBe null
        }

        @Test
        fun `records drop when buffer은(는) full이다`() = runTest {
            val tinyBuffer = MetricRingBuffer(64)
            val tinyHook = MetricCollectionHook(tinyBuffer, healthMonitor)

            repeat(64) { tinyBuffer.publish(AgentExecutionEvent(tenantId = "t", runId = "r-$it", success = true)) }

            val dropsBefore = healthMonitor.droppedTotal.get()
            tinyHook.afterToolCall(toolCallContext(), toolCallResult())
            healthMonitor.droppedTotal.get() shouldBe dropsBefore + 1
        }

        @Test
        fun `metadata has no tenantId일 때 default tenantId를 사용한다`() = runTest {
            hook.afterToolCall(toolCallContext(metadata = mutableMapOf()), toolCallResult())

            val event = ringBuffer.drain(10).filterIsInstance<ToolCallEvent>().single()
            event.tenantId shouldBe "default"
        }
    }

    @Nested
    inner class GuardEventEmission {

        @Test
        fun `guard allowed event when guardDurationMs present를 방출한다`() = runTest {
            val context = hookContext(metadata = mutableMapOf("tenantId" to "tenant-1", "guardDurationMs" to "15"))
            hook.afterAgentComplete(context, agentResponse())

            val guardEvent = ringBuffer.drain(10).filterIsInstance<GuardEvent>().single()
            guardEvent.tenantId shouldBe "tenant-1"
            guardEvent.action shouldBe "allowed"
            guardEvent.stage shouldBe "all"
            guardEvent.category shouldBe "none"
        }

        @Test
        fun `emit guard event when guardDurationMs absent하지 않는다`() = runTest {
            hook.afterAgentComplete(hookContext(), agentResponse())

            val guardEvents = ringBuffer.drain(10).filterIsInstance<GuardEvent>()
            guardEvents.size shouldBe 0
        }
    }

    @Nested
    inner class SessionEventEmission {

        @Test
        fun `does not emit session event when identifier storage은(는) disabled이다`() = runTest {
            val context = hookContext(metadata = mutableMapOf("tenantId" to "tenant-1", "sessionId" to "sess-42"))
            hook.afterAgentComplete(context, agentResponse(totalDurationMs = 750))

            val sessionEvents = ringBuffer.drain(10).filterIsInstance<SessionEvent>()
            sessionEvents.size shouldBe 0
        }

        @Test
        fun `session event when sessionId present를 방출한다`() = runTest {
            val sessionEnabledHook = MetricCollectionHook(
                ringBuffer = ringBuffer,
                healthMonitor = healthMonitor,
                storeUserIdentifiers = true,
                storeSessionIdentifiers = true
            )
            val context = hookContext(metadata = mutableMapOf("tenantId" to "tenant-1", "sessionId" to "sess-42"))
            sessionEnabledHook.afterAgentComplete(context, agentResponse(totalDurationMs = 750))

            val sessionEvent = ringBuffer.drain(10).filterIsInstance<SessionEvent>().single()
            sessionEvent.tenantId shouldBe "tenant-1"
            sessionEvent.sessionId shouldBe "sess-42"
            sessionEvent.userId shouldBe "user-1"
            sessionEvent.turnCount shouldBe 1
            sessionEvent.totalDurationMs shouldBe 750
        }

        @Test
        fun `emit session event when sessionId absent하지 않는다`() = runTest {
            hook.afterAgentComplete(hookContext(), agentResponse())

            val sessionEvents = ringBuffer.drain(10).filterIsInstance<SessionEvent>()
            sessionEvents.size shouldBe 0
        }
    }

    @Nested
    inner class McpHealthEventEmission {

        private fun toolCallContext(
            toolName: String = "check_order",
            callIndex: Int = 0,
            metadata: MutableMap<String, Any> = mutableMapOf("tenantId" to "tenant-1")
        ) = ToolCallContext(
            agentContext = hookContext(metadata = metadata),
            toolName = toolName,
            toolParams = emptyMap(),
            callIndex = callIndex
        )

        private fun toolCallResult(
            success: Boolean = true,
            durationMs: Long = 100,
            errorMessage: String? = null
        ) = ToolCallResult(
            success = success,
            output = "result",
            errorMessage = errorMessage,
            durationMs = durationMs
        )

        @Test
        fun `MCP health event for MCP tool calls를 방출한다`() = runTest {
            val meta = mutableMapOf<String, Any>(
                "tenantId" to "tenant-1",
                "toolSource_check_order" to "mcp",
                "mcpServer_check_order" to "payment-server"
            )
            hook.afterToolCall(toolCallContext(metadata = meta), toolCallResult(durationMs = 45))

            val mcpEvent = ringBuffer.drain(10).filterIsInstance<McpHealthEvent>().single()
            mcpEvent.tenantId shouldBe "tenant-1"
            mcpEvent.serverName shouldBe "payment-server"
            mcpEvent.status shouldBe "CONNECTED"
            mcpEvent.responseTimeMs shouldBe 45
            mcpEvent.errorClass shouldBe null
        }

        @Test
        fun `FAILED status for failed MCP tool calls를 방출한다`() = runTest {
            val meta = mutableMapOf<String, Any>(
                "tenantId" to "tenant-1",
                "toolSource_check_order" to "mcp",
                "mcpServer_check_order" to "payment-server"
            )
            hook.afterToolCall(
                toolCallContext(metadata = meta),
                toolCallResult(success = false, errorMessage = "Connection timeout after 15s")
            )

            val mcpEvent = ringBuffer.drain(10).filterIsInstance<McpHealthEvent>().single()
            mcpEvent.status shouldBe "FAILED"
            mcpEvent.errorClass shouldBe "timeout"
            mcpEvent.errorMessage shouldBe "Connection timeout after 15s"
        }

        @Test
        fun `emit MCP health event for local tool calls하지 않는다`() = runTest {
            hook.afterToolCall(toolCallContext(), toolCallResult())

            val mcpEvents = ringBuffer.drain(10).filterIsInstance<McpHealthEvent>()
            mcpEvents.size shouldBe 0
        }
    }
}
