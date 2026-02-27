package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.GuardEvent
import com.arc.reactor.admin.model.TokenUsageEvent
import com.arc.reactor.admin.pricing.CostCalculator
import com.arc.reactor.admin.pricing.InMemoryModelPricingStore
import com.arc.reactor.admin.pricing.ModelPricing
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.resilience.CircuitBreakerState
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MetricCollectorAgentMetricsTest {

    private lateinit var ringBuffer: MetricRingBuffer
    private val healthMonitor = PipelineHealthMonitor()
    private val pricingStore = InMemoryModelPricingStore()
    private val costCalculator = CostCalculator(pricingStore)
    private lateinit var metrics: MetricCollectorAgentMetrics
    private val defaultMetadata = mapOf<String, Any>("tenantId" to "tenant-1")

    @BeforeEach
    fun setUp() {
        ringBuffer = MetricRingBuffer(64)
        metrics = MetricCollectorAgentMetrics(ringBuffer, healthMonitor, costCalculator)
    }

    @Nested
    inner class DelegatedToHook {

        @Test
        fun `execution and tool metrics are delegated to hook as no-op`() {
            val result = AgentResult(success = true, content = "ok")
            metrics.recordExecution(result)
            metrics.recordToolCall("check_order", 100, true)
            metrics.recordStreamingExecution(result)
            ringBuffer.size() shouldBe 0
        }
    }

    @Nested
    inner class GuardRejection {

        @Test
        fun `publishes guard event with correct classification`() {
            metrics.recordGuardRejection("RateLimitStage", "Too many requests", defaultMetadata)

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
            metrics.recordGuardRejection("InjectionDetectionStage", "SQL injection detected", defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "prompt_injection"
        }

        @Test
        fun `classifies classification stage`() {
            metrics.recordGuardRejection("ClassificationStage", "Off-topic", defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "classification"
        }

        @Test
        fun `classifies permission stage`() {
            metrics.recordGuardRejection("PermissionStage", "No access", defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "permission"
        }

        @Test
        fun `classifies input validation stage`() {
            metrics.recordGuardRejection("InputValidationStage", "Too long", defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "input_validation"
        }

        @Test
        fun `classifies unknown stage as other`() {
            metrics.recordGuardRejection("CustomStage", "Custom reason", defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "other"
        }

        @Test
        fun `truncates reason to 500 chars`() {
            val longReason = "x".repeat(1000)
            metrics.recordGuardRejection("CustomStage", longReason, defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.reasonDetail!!.length shouldBe 500
        }

        @Test
        fun `uses default tenantId when metadata has no tenantId`() {
            metrics.recordGuardRejection("CustomStage", "reason", emptyMap())

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.tenantId shouldBe "default"
        }
    }

    @Nested
    inner class TokenUsageRecording {

        @Test
        fun `publishes token usage event with metadata extraction`() {
            val metadata = mapOf<String, Any>(
                "tenantId" to "tenant-1",
                "runId" to "run-42",
                "model" to "gemini-2.0-flash",
                "provider" to "google"
            )
            metrics.recordTokenUsage(
                TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150),
                metadata
            )

            val events = ringBuffer.drain(10)
            events.size shouldBe 1
            val event = events[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.tenantId shouldBe "tenant-1"
            event.runId shouldBe "run-42"
            event.model shouldBe "gemini-2.0-flash"
            event.provider shouldBe "google"
            event.promptTokens shouldBe 100
            event.completionTokens shouldBe 50
            event.totalTokens shouldBe 150
        }

        @Test
        fun `derives provider from model name when not in metadata`() {
            metrics.recordTokenUsage(
                TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
                mapOf("tenantId" to "t1", "model" to "gpt-4o")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.model shouldBe "gpt-4o"
            event.provider shouldBe "openai"
        }

        @Test
        fun `derives anthropic provider from claude model`() {
            metrics.recordTokenUsage(
                TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
                mapOf("tenantId" to "t1", "model" to "claude-3-sonnet")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.provider shouldBe "anthropic"
        }

        @Test
        fun `uses unknown when metadata has no model or provider`() {
            metrics.recordTokenUsage(
                TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
                defaultMetadata
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.model shouldBe "unknown"
            event.provider shouldBe "unknown"
        }

        @Test
        fun `uses default tenantId when metadata has no tenantId`() {
            metrics.recordTokenUsage(
                TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
                emptyMap()
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.tenantId shouldBe "default"
        }

        @Test
        fun `calculates cost for known model pricing`() {
            pricingStore.save(
                ModelPricing(
                    provider = "openai",
                    model = "gpt-4",
                    promptPricePer1k = BigDecimal("0.03"),
                    completionPricePer1k = BigDecimal("0.06")
                )
            )

            metrics.recordTokenUsage(
                TokenUsage(promptTokens = 1000, completionTokens = 500, totalTokens = 1500),
                mapOf("tenantId" to "tenant-1", "model" to "gpt-4")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.estimatedCostUsd shouldBeGreaterThan BigDecimal.ZERO
            event.provider shouldBe "openai"
        }

        @Test
        fun `returns zero cost for unknown model pricing`() {
            metrics.recordTokenUsage(
                TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150),
                mapOf("tenantId" to "tenant-1", "model" to "unknown-model-xyz")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.estimatedCostUsd shouldBe BigDecimal.ZERO
        }
    }

    @Nested
    inner class OutputGuardAction {

        @Test
        fun `publishes output guard event`() {
            metrics.recordOutputGuardAction(
                "ContentFilterStage", "modified", "PII detected and redacted", defaultMetadata
            )

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
            metrics.recordOutputGuardAction("Stage", "rejected", longReason, defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.reasonDetail!!.length shouldBe 500
        }

        @Test
        fun `uses default tenantId when metadata has no tenantId`() {
            metrics.recordOutputGuardAction("Stage", "allowed", "", emptyMap())

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.tenantId shouldBe "default"
        }
    }

    @Nested
    inner class DropTracking {

        @Test
        fun `records drop when buffer is full`() {
            // Min capacity is 64 (MetricRingBuffer coerces to at least 64)
            val tinyBuffer = MetricRingBuffer(64)
            val tinyMetrics = MetricCollectorAgentMetrics(tinyBuffer, healthMonitor, costCalculator)

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
        fun `non-pipeline metrics methods are no-op`() {
            metrics.recordCacheHit("key")
            metrics.recordCacheMiss("key")
            metrics.recordCircuitBreakerStateChange("cb", CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN)
            metrics.recordFallbackAttempt("gpt-4", true)
            metrics.recordBoundaryViolation("input_too_long", "max_input", 5000, 6000)
            ringBuffer.size() shouldBe 0
        }
    }
}
