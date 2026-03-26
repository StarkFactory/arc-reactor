package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.AgentExecutionEvent
import com.arc.reactor.admin.model.MetricEvent
import com.arc.reactor.admin.model.TokenUsageEvent
import com.arc.reactor.admin.pricing.CostCalculator
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** [MetricWriter]의 비용 보강, 헬스 추적, 플러시 직렬화, 라이프사이클 테스트 */
class MetricWriterTest {

    private val ringBuffer = MetricRingBuffer(128)
    private val healthMonitor = PipelineHealthMonitor()
    private val costCalculator = mockk<CostCalculator>()
    private val storedEvents = CopyOnWriteArrayList<MetricEvent>()

    private val store = object : MetricEventStore {
        override fun batchInsert(events: List<MetricEvent>) {
            storedEvents.addAll(events)
        }
    }

    private fun tokenEvent(
        model: String = "gemini-2.5-flash",
        provider: String = "google",
        promptTokens: Int = 100,
        completionTokens: Int = 50,
        cost: BigDecimal = BigDecimal.ZERO
    ) = TokenUsageEvent(
        tenantId = "tenant-1",
        runId = "run-1",
        model = model,
        provider = provider,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = promptTokens + completionTokens,
        estimatedCostUsd = cost
    )

    private fun executionEvent() = AgentExecutionEvent(
        tenantId = "tenant-1",
        runId = "run-1",
        success = true
    )

    @Nested
    inner class CostEnrichment {

        @Test
        fun `enriches은(는) TokenUsageEvent with calculated cost`() {
            every {
                costCalculator.calculate(
                    provider = "google",
                    model = "gemini-2.5-flash",
                    time = any(),
                    promptTokens = 100,
                    cachedTokens = 0,
                    completionTokens = 50,
                    reasoningTokens = 0
                )
            } returns BigDecimal("0.0025")

            ringBuffer.publish(tokenEvent())

            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                batchSize = 10,
                flushIntervalMs = 60000,  // long interval — stop() triggers final flush
                writerThreads = 1,
                healthMonitor = healthMonitor
            )
            writer.start()
            writer.stop()

            storedEvents.size shouldBe 1
            val enriched = storedEvents[0].shouldBeInstanceOf<TokenUsageEvent>()
            enriched.estimatedCostUsd shouldBe BigDecimal("0.0025")
        }

        @Test
        fun `enrich when cost already set하지 않는다`() {
            val existingCost = BigDecimal("0.005")
            ringBuffer.publish(tokenEvent(cost = existingCost))

            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                batchSize = 10,
                flushIntervalMs = 60000,
                writerThreads = 1,
                healthMonitor = healthMonitor
            )
            writer.start()
            writer.stop()

            storedEvents.size shouldBe 1
            val stored = storedEvents[0].shouldBeInstanceOf<TokenUsageEvent>()
            stored.estimatedCostUsd shouldBe existingCost

            // CostCalculator가 호출되지 않아야 합니다
            verify(exactly = 0) { costCalculator.calculate(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `cost calculation returns zero일 때 keeps original event`() {
            every {
                costCalculator.calculate(any(), any(), any(), any(), any(), any(), any())
            } returns BigDecimal.ZERO

            ringBuffer.publish(tokenEvent())

            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                batchSize = 10,
                flushIntervalMs = 60000,
                writerThreads = 1,
                healthMonitor = healthMonitor
            )
            writer.start()
            writer.stop()

            storedEvents.size shouldBe 1
            val stored = storedEvents[0].shouldBeInstanceOf<TokenUsageEvent>()
            stored.estimatedCostUsd shouldBe BigDecimal.ZERO
        }

        @Test
        fun `cost calculation throws일 때 keeps original event`() {
            every {
                costCalculator.calculate(any(), any(), any(), any(), any(), any(), any())
            } throws RuntimeException("pricing DB down")

            ringBuffer.publish(tokenEvent())

            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                batchSize = 10,
                flushIntervalMs = 60000,
                writerThreads = 1,
                healthMonitor = healthMonitor
            )
            writer.start()
            writer.stop()

            storedEvents.size shouldBe 1
            val stored = storedEvents[0].shouldBeInstanceOf<TokenUsageEvent>()
            stored.estimatedCostUsd shouldBe BigDecimal.ZERO
        }

        @Test
        fun `through non-token events unchanged를 전달한다`() {
            ringBuffer.publish(executionEvent())

            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                batchSize = 10,
                flushIntervalMs = 60000,
                writerThreads = 1,
                healthMonitor = healthMonitor
            )
            writer.start()
            writer.stop()

            storedEvents.size shouldBe 1
            storedEvents[0].shouldBeInstanceOf<AgentExecutionEvent>()

            verify(exactly = 0) { costCalculator.calculate(any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class HealthTracking {

        @Test
        fun `write count and latency on successful flush를 기록한다`() {
            ringBuffer.publish(executionEvent())

            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                batchSize = 10,
                flushIntervalMs = 60000,
                writerThreads = 1,
                healthMonitor = healthMonitor
            )
            writer.start()
            writer.stop()

            healthMonitor.writtenTotal.get() shouldBe 1
        }

        @Test
        fun `write error on store failure를 기록한다`() {
            val failStore = object : MetricEventStore {
                override fun batchInsert(events: List<MetricEvent>) {
                    throw RuntimeException("DB unavailable")
                }
            }

            ringBuffer.publish(executionEvent())

            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = failStore,
                costCalculator = costCalculator,
                batchSize = 10,
                flushIntervalMs = 60000,
                writerThreads = 1,
                healthMonitor = healthMonitor
            )
            writer.start()
            writer.stop()

            healthMonitor.writeErrorsTotal.get() shouldBe 1
        }
    }

    @Nested
    inner class FlushSerialization {

        @Test
        fun `concurrent은(는) flush calls do not cause duplicate events`() {
            every {
                costCalculator.calculate(any(), any(), any(), any(), any(), any(), any())
            } returns BigDecimal("0.001")

            // 100개 이벤트 발행
            repeat(100) { i ->
                ringBuffer.publish(tokenEvent(model = "model-$i"))
            }

            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                batchSize = 10,
                flushIntervalMs = 10, // fast flush
                writerThreads = 3, // multiple threads, but flush is serialized by lock
                healthMonitor = healthMonitor
            )
            writer.start()

            await atMost Duration.ofSeconds(5) untilAsserted {
                // 모든 이벤트가 정확히 한 번만 저장되어야 합니다 (중복 없음)
                storedEvents.size shouldBe 100
            }
            writer.stop()
        }
    }

    @Nested
    inner class Lifecycle {

        @Test
        fun `start은(는) idempotent이다`() {
            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                healthMonitor = healthMonitor
            )
            writer.start()
            writer.start()  // 두 번째 호출은 아무 동작도 하지 않아야 합니다
            writer.stop()
        }

        @Test
        fun `stop은(는) idempotent이다`() {
            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                healthMonitor = healthMonitor
            )
            writer.start()
            writer.stop()
            writer.stop()  // 두 번째 호출은 아무 동작도 하지 않아야 합니다
        }

        @Test
        fun `flushes은(는) remaining events on stop`() {
            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                batchSize = 1000,
                flushIntervalMs = 60000, // very long, won't auto-flush
                writerThreads = 1,
                healthMonitor = healthMonitor
            )

            every {
                costCalculator.calculate(any(), any(), any(), any(), any(), any(), any())
            } returns BigDecimal("0.001")

            // start 이후에 이벤트를 발행하여 버퍼에 넣음
            writer.start()
            ringBuffer.publish(tokenEvent())
            ringBuffer.publish(executionEvent())

            // Stop은(는) trigger final flush해야 합니다
            writer.stop()

            storedEvents.size shouldBe 2
        }
    }
}
