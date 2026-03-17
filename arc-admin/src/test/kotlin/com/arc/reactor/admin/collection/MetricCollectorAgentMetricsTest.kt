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

/** [MetricCollectorAgentMetrics]의 가드 거부, 토큰 사용량, 출력 가드 이벤트 발행 테스트 */
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
        fun `execution and tool metrics은(는) delegated to hook as no-op이다`() {
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
        fun `guard event with correct classification를 발행한다`() {
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
        fun `injection detection를 분류한다`() {
            metrics.recordGuardRejection("InjectionDetectionStage", "SQL injection detected", defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "prompt_injection"
        }

        @Test
        fun `classification stage를 분류한다`() {
            metrics.recordGuardRejection("ClassificationStage", "Off-topic", defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "classification"
        }

        @Test
        fun `permission stage를 분류한다`() {
            metrics.recordGuardRejection("PermissionStage", "No access", defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "permission"
        }

        @Test
        fun `input validation stage를 분류한다`() {
            metrics.recordGuardRejection("InputValidationStage", "Too long", defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "input_validation"
        }

        @Test
        fun `unknown stage as other를 분류한다`() {
            metrics.recordGuardRejection("CustomStage", "Custom reason", defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.category shouldBe "other"
        }

        @Test
        fun `reason to 500 chars를 잘라낸다`() {
            val longReason = "x".repeat(1000)
            metrics.recordGuardRejection("CustomStage", longReason, defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.reasonDetail!!.length shouldBe 500
        }

        @Test
        fun `metadata has no tenantId일 때 default tenantId를 사용한다`() {
            metrics.recordGuardRejection("CustomStage", "reason", emptyMap())

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.tenantId shouldBe "default"
        }
    }

    @Nested
    inner class TokenUsageRecording {

        @Test
        fun `token usage event with metadata extraction를 발행한다`() {
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
        fun `not in metadata일 때 derives provider from model name`() {
            metrics.recordTokenUsage(
                TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
                mapOf("tenantId" to "t1", "model" to "gpt-4o")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.model shouldBe "gpt-4o"
            event.provider shouldBe "openai"
        }

        @Test
        fun `derives은(는) anthropic provider from claude model`() {
            metrics.recordTokenUsage(
                TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
                mapOf("tenantId" to "t1", "model" to "claude-3-sonnet")
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.provider shouldBe "anthropic"
        }

        @Test
        fun `metadata has no model or provider일 때 unknown를 사용한다`() {
            metrics.recordTokenUsage(
                TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
                defaultMetadata
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.model shouldBe "unknown"
            event.provider shouldBe "unknown"
        }

        @Test
        fun `metadata has no tenantId일 때 default tenantId를 사용한다`() {
            metrics.recordTokenUsage(
                TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
                emptyMap()
            )

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<TokenUsageEvent>()
            event.tenantId shouldBe "default"
        }

        @Test
        fun `cost for known model pricing를 계산한다`() {
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
        fun `unknown model pricing에 대해 zero cost를 반환한다`() {
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
        fun `output guard event를 발행한다`() {
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
        fun `output guard reason to 500 chars를 잘라낸다`() {
            val longReason = "y".repeat(800)
            metrics.recordOutputGuardAction("Stage", "rejected", longReason, defaultMetadata)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.reasonDetail!!.length shouldBe 500
        }

        @Test
        fun `metadata has no tenantId일 때 default tenantId를 사용한다`() {
            metrics.recordOutputGuardAction("Stage", "allowed", "", emptyMap())

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<GuardEvent>()
            event.tenantId shouldBe "default"
        }
    }

    @Nested
    inner class DropTracking {

        @Test
        fun `records drop when buffer은(는) full이다`() {
            // 최소 용량은 64입니다 (MetricRingBuffer가 최소 64로 강제함)
            val tinyBuffer = MetricRingBuffer(64)
            val tinyMetrics = MetricCollectorAgentMetrics(tinyBuffer, healthMonitor, costCalculator)

            // buffer to capacity를 채웁니다
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
        fun `counters update without publishing ring-buffer events를 캐시한다`() {
            metrics.recordCacheHit("key")
            metrics.recordExactCacheHit("key")
            metrics.recordSemanticCacheHit("key")
            metrics.recordCacheMiss("key")

            healthMonitor.cacheExactHitsTotal.get() shouldBe 2
            healthMonitor.cacheSemanticHitsTotal.get() shouldBe 1
            healthMonitor.cacheMissesTotal.get() shouldBe 1
            ringBuffer.size() shouldBe 0
        }

        @Test
        fun `remaining non-pipeline metrics methods은(는) no-op이다`() {
            metrics.recordCircuitBreakerStateChange("cb", CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN)
            metrics.recordFallbackAttempt("gpt-4", true)
            metrics.recordBoundaryViolation("input_too_long", "max_input", 5000, 6000)
            ringBuffer.size() shouldBe 0
        }
    }
}
