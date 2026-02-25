package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.AgentExecutionEvent
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
        totalDurationMs: Long = 500
    ) = AgentResponse(
        success = success,
        response = "response",
        toolsUsed = toolsUsed,
        totalDurationMs = totalDurationMs
    )

    @Nested
    inner class Properties {

        @Test
        fun `hook properties are correct`() {
            hook.order shouldBe 200
            hook.enabled shouldBe true
            hook.failOnError shouldBe false
        }
    }

    @Nested
    inner class AfterAgentComplete {

        @Test
        fun `publishes execution event on success`() = runTest {
            val context = hookContext(metadata = mutableMapOf("tenantId" to "tenant-1", "sessionId" to "sess-1"))
            hook.afterAgentComplete(context, agentResponse(success = true, toolsUsed = listOf("tool1", "tool2")))

            val events = ringBuffer.drain(10)
            events.size shouldBe 1
            val event = events[0].shouldBeInstanceOf<AgentExecutionEvent>()
            event.tenantId shouldBe "tenant-1"
            event.runId shouldBe "run-1"
            event.userId shouldBe "user-1"
            event.sessionId shouldBe "sess-1"
            event.success shouldBe true
            event.toolCount shouldBe 2
            event.durationMs shouldBe 500
        }

        @Test
        fun `publishes execution event on failure with errorCode`() = runTest {
            val context = hookContext(metadata = mutableMapOf("tenantId" to "tenant-1", "errorCode" to "TOOL_ERROR"))
            hook.afterAgentComplete(context, agentResponse(success = false))

            val events = ringBuffer.drain(10)
            events.size shouldBe 1
            val event = events[0].shouldBeInstanceOf<AgentExecutionEvent>()
            event.success shouldBe false
            event.errorCode shouldBe "TOOL_ERROR"
        }

        @Test
        fun `errorCode is null when success is true even if metadata has errorCode`() = runTest {
            val context = hookContext(metadata = mutableMapOf("tenantId" to "tenant-1", "errorCode" to "TOOL_ERROR"))
            hook.afterAgentComplete(context, agentResponse(success = true))

            val events = ringBuffer.drain(10)
            val event = events[0].shouldBeInstanceOf<AgentExecutionEvent>()
            event.errorCode shouldBe null
        }

        @Test
        fun `extracts latency breakdown from metadata`() = runTest {
            val context = hookContext(metadata = mutableMapOf(
                "tenantId" to "tenant-1",
                "llmDurationMs" to "300",
                "toolDurationMs" to "150",
                "guardDurationMs" to "20",
                "queueWaitMs" to "30"
            ))
            hook.afterAgentComplete(context, agentResponse())

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<AgentExecutionEvent>()
            event.llmDurationMs shouldBe 300
            event.toolDurationMs shouldBe 150
            event.guardDurationMs shouldBe 20
            event.queueWaitMs shouldBe 30
        }

        @Test
        fun `missing metadata values default to zero`() = runTest {
            hook.afterAgentComplete(hookContext(), agentResponse())

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<AgentExecutionEvent>()
            event.llmDurationMs shouldBe 0
            event.toolDurationMs shouldBe 0
            event.guardDurationMs shouldBe 0
            event.queueWaitMs shouldBe 0
            event.sessionId shouldBe null
            event.personaId shouldBe null
            event.intentCategory shouldBe null
        }

        @Test
        fun `records drop when buffer is full`() = runTest {
            // Min capacity is 64 (MetricRingBuffer coerces to at least 64)
            val tinyBuffer = MetricRingBuffer(64)
            val tinyHook = MetricCollectionHook(tinyBuffer, healthMonitor)

            // Fill buffer to capacity
            repeat(64) { tinyBuffer.publish(AgentExecutionEvent(tenantId = "t", runId = "r-$it", success = true)) }

            val dropsBefore = healthMonitor.droppedTotal.get()
            tinyHook.afterAgentComplete(hookContext(), agentResponse())
            healthMonitor.droppedTotal.get() shouldBe dropsBefore + 1
        }

        @Test
        fun `uses default tenantId when metadata has no tenantId`() = runTest {
            hook.afterAgentComplete(hookContext(metadata = mutableMapOf()), agentResponse())

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<AgentExecutionEvent>()
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
        fun `publishes tool call event on success`() = runTest {
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
        fun `classifies timeout error`() = runTest {
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = "Connection timeout after 15000ms")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.success shouldBe false
            event.errorClass shouldBe "timeout"
        }

        @Test
        fun `classifies connection error`() = runTest {
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = "Connection refused to host")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.errorClass shouldBe "connection_error"
        }

        @Test
        fun `classifies permission denied error`() = runTest {
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = "Permission denied for resource X")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.errorClass shouldBe "permission_denied"
        }

        @Test
        fun `classifies not found error`() = runTest {
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = "Resource not found")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.errorClass shouldBe "not_found"
        }

        @Test
        fun `classifies unknown error`() = runTest {
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = "Something totally unexpected")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.errorClass shouldBe "unknown"
        }

        @Test
        fun `truncates error message to 500 chars`() = runTest {
            val longMessage = "x".repeat(1000)
            hook.afterToolCall(
                toolCallContext(),
                toolCallResult(success = false, errorMessage = longMessage)
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.errorMessage!!.length shouldBe 500
        }

        @Test
        fun `resolves tool source from metadata`() = runTest {
            val meta = mutableMapOf<String, Any>(
                "tenantId" to "tenant-1",
                "toolSource_check_order" to "mcp",
                "mcpServer_check_order" to "payment-server"
            )
            hook.afterToolCall(toolCallContext(metadata = meta), toolCallResult())

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.toolSource shouldBe "mcp"
            event.mcpServerName shouldBe "payment-server"
        }

        @Test
        fun `defaults tool source to local`() = runTest {
            hook.afterToolCall(toolCallContext(), toolCallResult())

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.toolSource shouldBe "local"
            event.mcpServerName shouldBe null
        }

        @Test
        fun `records drop when buffer is full`() = runTest {
            val tinyBuffer = MetricRingBuffer(64)
            val tinyHook = MetricCollectionHook(tinyBuffer, healthMonitor)

            repeat(64) { tinyBuffer.publish(AgentExecutionEvent(tenantId = "t", runId = "r-$it", success = true)) }

            val dropsBefore = healthMonitor.droppedTotal.get()
            tinyHook.afterToolCall(toolCallContext(), toolCallResult())
            healthMonitor.droppedTotal.get() shouldBe dropsBefore + 1
        }

        @Test
        fun `uses default tenantId when metadata has no tenantId`() = runTest {
            hook.afterToolCall(toolCallContext(metadata = mutableMapOf()), toolCallResult())

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.tenantId shouldBe "default"
        }
    }
}
