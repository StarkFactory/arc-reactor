package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.GuardEvent
import com.arc.reactor.admin.model.TokenUsageEvent
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.resilience.CircuitBreakerState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MetricCollectorAgentMetricsTest {

    private lateinit var ringBuffer: MetricRingBuffer
    private val tenantResolver = TenantResolver()
    private val healthMonitor = PipelineHealthMonitor()
    private lateinit var metrics: MetricCollectorAgentMetrics

    @BeforeEach
    fun setUp() {
        ringBuffer = MetricRingBuffer(64)
        tenantResolver.setTenantId("tenant-1")
        metrics = MetricCollectorAgentMetrics(ringBuffer, tenantResolver, healthMonitor)
    }

    @Nested
    inner class DelegatedToHook {

        @Test
        fun `recordExecution is no-op (handled by MetricCollectionHook)`() {
            val result = AgentResult(success = true, content = "ok")
            metrics.recordExecution(result)
            ringBuffer.size() shouldBe 0
        }

        @Test
        fun `recordToolCall is no-op (handled by MetricCollectionHook)`() {
            metrics.recordToolCall("check_order", 100, true)
            ringBuffer.size() shouldBe 0
        }

        @Test
        fun `recordStreamingExecution is no-op (handled by MetricCollectionHook)`() {
            val result = AgentResult(success = true, content = "ok")
            metrics.recordStreamingExecution(result)
            ringBuffer.size() shouldBe 0
        }
    }

    @Nested
    inner class GuardRejection {

        @Test
        fun `publishes guard event with correct classification`() {
            metrics.recordGuardRejection("RateLimitStage", "Too many requests")

            val events = ringBuffer.drain(10)
            events.size shouldBe 1
            val event = events[0].shouldBeInstanceOf<GuardEvent>()
            event.tenantId shouldBe "tenant-1"
            event.stage shouldBe "RateLimitStage"
            event.category shouldBe "rate_limit"
            event.reasonDetail shouldBe "Too many requests"
            event.isOutputGuard shouldBe false
        }

        @Test
        fun `classifies injection detection`() {
            metrics.recordGuardRejection("InjectionDetectionStage", "SQL injection detected")

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "prompt_injection"
        }

        @Test
        fun `classifies classification stage`() {
            metrics.recordGuardRejection("ClassificationStage", "Off-topic")

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "classification"
        }

        @Test
        fun `classifies permission stage`() {
            metrics.recordGuardRejection("PermissionStage", "No access")

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "permission"
        }

        @Test
        fun `classifies input validation stage`() {
            metrics.recordGuardRejection("InputValidationStage", "Too long")

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "input_validation"
        }

        @Test
        fun `classifies unknown stage as other`() {
            metrics.recordGuardRejection("CustomStage", "Custom reason")

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "other"
        }

        @Test
        fun `truncates reason to 500 chars`() {
            val longReason = "x".repeat(1000)
            metrics.recordGuardRejection("CustomStage", longReason)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.reasonDetail!!.length shouldBe 500
        }
    }

    @Nested
    inner class TokenUsageRecording {

        @Test
        fun `publishes token usage event`() {
            metrics.recordTokenUsage(TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150))

            val events = ringBuffer.drain(10)
            events.size shouldBe 1
            val event = events[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.tenantId shouldBe "tenant-1"
            event.promptTokens shouldBe 100
            event.completionTokens shouldBe 50
            event.totalTokens shouldBe 150
            event.model shouldBe "unknown"
            event.provider shouldBe "unknown"
        }
    }

    @Nested
    inner class OutputGuardAction {

        @Test
        fun `publishes output guard event`() {
            metrics.recordOutputGuardAction("ContentFilterStage", "modified", "PII detected and redacted")

            val events = ringBuffer.drain(10)
            events.size shouldBe 1
            val event = events[0].shouldBeInstanceOf<GuardEvent>()
            event.tenantId shouldBe "tenant-1"
            event.stage shouldBe "ContentFilterStage"
            event.category shouldBe "output_guard"
            event.isOutputGuard shouldBe true
            event.action shouldBe "modified"
            event.reasonDetail shouldBe "PII detected and redacted"
        }

        @Test
        fun `truncates output guard reason to 500 chars`() {
            val longReason = "y".repeat(800)
            metrics.recordOutputGuardAction("Stage", "rejected", longReason)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.reasonDetail!!.length shouldBe 500
        }
    }

    @Nested
    inner class DropTracking {

        @Test
        fun `records drop when buffer is full`() {
            // Min capacity is 64 (MetricRingBuffer coerces to at least 64)
            val tinyBuffer = MetricRingBuffer(64)
            val tinyMetrics = MetricCollectorAgentMetrics(tinyBuffer, tenantResolver, healthMonitor)

            // Fill buffer to capacity
            repeat(64) {
                tinyBuffer.publish(
                    com.arc.reactor.admin.model.AgentExecutionEvent(tenantId = "t", runId = "r-$it", success = true)
                )
            }

            val dropsBefore = healthMonitor.droppedTotal.get()
            tinyMetrics.recordGuardRejection("Stage", "reason")
            healthMonitor.droppedTotal.get() shouldBe dropsBefore + 1
        }
    }

    @Nested
    inner class NoOpMethods {

        @Test
        fun `cache hit is no-op`() {
            metrics.recordCacheHit("key")
            ringBuffer.size() shouldBe 0
        }

        @Test
        fun `cache miss is no-op`() {
            metrics.recordCacheMiss("key")
            ringBuffer.size() shouldBe 0
        }

        @Test
        fun `circuit breaker state change is no-op`() {
            metrics.recordCircuitBreakerStateChange("cb", CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN)
            ringBuffer.size() shouldBe 0
        }

        @Test
        fun `fallback attempt is no-op`() {
            metrics.recordFallbackAttempt("gpt-4", true)
            ringBuffer.size() shouldBe 0
        }

        @Test
        fun `boundary violation is no-op`() {
            metrics.recordBoundaryViolation("input_too_long", "max_input", 5000, 6000)
            ringBuffer.size() shouldBe 0
        }
    }
}
