package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.AgentExecutionEvent
import com.arc.reactor.admin.model.HitlEvent
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HitlEventHookTest {

    private lateinit var ringBuffer: MetricRingBuffer
    private val healthMonitor = PipelineHealthMonitor()
    private lateinit var hook: HitlEventHook

    @BeforeEach
    fun setUp() {
        ringBuffer = MetricRingBuffer(64)
        hook = HitlEventHook(ringBuffer, healthMonitor)
    }

    private fun toolCallContext(
        toolName: String,
        metadata: MutableMap<String, Any> = mutableMapOf()
    ): ToolCallContext = ToolCallContext(
        agentContext = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "test prompt",
            metadata = metadata
        ),
        toolName = toolName,
        toolParams = emptyMap(),
        callIndex = 0
    )

    private val successResult = ToolCallResult(success = true, durationMs = 100)

    @Nested
    inner class ApprovedToolCall {

        @Test
        fun `publishes HitlEvent with approved=true when tool approved`() = runTest {
            val ctx = toolCallContext(
                "send_email",
                mutableMapOf(
                    "tenantId" to "tenant-1",
                    "hitlWaitMs_send_email" to "5000",
                    "hitlApproved_send_email" to "true"
                )
            )

            hook.afterToolCall(ctx, successResult)

            val events = ringBuffer.drain(10)
            events.size shouldBe 1
            val event = events[0].shouldBeInstanceOf<HitlEvent>()
            event.tenantId shouldBe "tenant-1"
            event.runId shouldBe "run-1"
            event.toolName shouldBe "send_email"
            event.approved shouldBe true
            event.waitMs shouldBe 5000
            event.rejectionReason shouldBe null
        }
    }

    @Nested
    inner class RejectedToolCall {

        @Test
        fun `publishes HitlEvent with approved=false and reason when tool rejected`() = runTest {
            val ctx = toolCallContext(
                "delete_record",
                mutableMapOf(
                    "tenantId" to "tenant-1",
                    "hitlWaitMs_delete_record" to "3000",
                    "hitlApproved_delete_record" to "false",
                    "hitlRejectionReason_delete_record" to "Too risky"
                )
            )

            hook.afterToolCall(ctx, successResult)

            val events = ringBuffer.drain(10)
            events.size shouldBe 1
            val event = events[0].shouldBeInstanceOf<HitlEvent>()
            event.approved shouldBe false
            event.waitMs shouldBe 3000
            event.rejectionReason shouldBe "Too risky"
        }
    }

    @Nested
    inner class NoHitlMetadata {

        @Test
        fun `skips event when no HITL metadata present`() = runTest {
            val ctx = toolCallContext(
                "check_order",
                mutableMapOf("tenantId" to "tenant-1")
            )

            hook.afterToolCall(ctx, successResult)

            ringBuffer.size() shouldBe 0
        }

        @Test
        fun `skips event when hitlWaitMs is not a valid number`() = runTest {
            val ctx = toolCallContext(
                "check_order",
                mutableMapOf(
                    "tenantId" to "tenant-1",
                    "hitlWaitMs_check_order" to "not-a-number"
                )
            )

            hook.afterToolCall(ctx, successResult)

            ringBuffer.size() shouldBe 0
        }
    }

    @Nested
    inner class DefaultValues {

        @Test
        fun `uses default tenantId when metadata has no tenantId`() = runTest {
            val ctx = toolCallContext(
                "send_email",
                mutableMapOf(
                    "hitlWaitMs_send_email" to "1000",
                    "hitlApproved_send_email" to "true"
                )
            )

            hook.afterToolCall(ctx, successResult)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<HitlEvent>()
            event.tenantId shouldBe "default"
        }

        @Test
        fun `defaults approved to true when hitlApproved not set`() = runTest {
            val ctx = toolCallContext(
                "send_email",
                mutableMapOf(
                    "tenantId" to "tenant-1",
                    "hitlWaitMs_send_email" to "2000"
                )
            )

            hook.afterToolCall(ctx, successResult)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<HitlEvent>()
            event.approved shouldBe true
        }
    }

    @Nested
    inner class BufferFull {

        @Test
        fun `records drop when buffer is full`() = runTest {
            val tinyBuffer = MetricRingBuffer(64)
            val tinyHook = HitlEventHook(tinyBuffer, healthMonitor)

            repeat(64) {
                tinyBuffer.publish(
                    AgentExecutionEvent(tenantId = "t", runId = "r-$it", success = true)
                )
            }

            val ctx = toolCallContext(
                "send_email",
                mutableMapOf(
                    "tenantId" to "tenant-1",
                    "hitlWaitMs_send_email" to "1000",
                    "hitlApproved_send_email" to "true"
                )
            )

            val dropsBefore = healthMonitor.droppedTotal.get()
            tinyHook.afterToolCall(ctx, successResult)
            healthMonitor.droppedTotal.get() shouldBe dropsBefore + 1
        }
    }

    @Nested
    inner class Properties {

        @Test
        fun `hook properties are correct`() {
            hook.order shouldBe 201
            hook.enabled shouldBe true
            hook.failOnError shouldBe false
        }
    }
}
