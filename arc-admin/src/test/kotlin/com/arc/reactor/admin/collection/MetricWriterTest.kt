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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList

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
        model: String = "gemini-2.0-flash",
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
        fun `enriches TokenUsageEvent with calculated cost`() {
            every {
                costCalculator.calculate(
                    provider = "google",
                    model = "gemini-2.0-flash",
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
                flushIntervalMs = 60000, // long interval, we'll flush manually
                writerThreads = 1,
                healthMonitor = healthMonitor
            )
            writer.start()

            // Wait for scheduled flush
            Thread.sleep(200)
            writer.stop()

            storedEvents.size shouldBe 1
            val enriched = storedEvents[0].shouldBeInstanceOf<TokenUsageEvent>()
            enriched.estimatedCostUsd shouldBe BigDecimal("0.0025")
        }

        @Test
        fun `does not enrich when cost already set`() {
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
            Thread.sleep(200)
            writer.stop()

            storedEvents.size shouldBe 1
            val stored = storedEvents[0].shouldBeInstanceOf<TokenUsageEvent>()
            stored.estimatedCostUsd shouldBe existingCost

            // CostCalculator should not have been called
            verify(exactly = 0) { costCalculator.calculate(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `keeps original event when cost calculation returns zero`() {
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
            Thread.sleep(200)
            writer.stop()

            storedEvents.size shouldBe 1
            val stored = storedEvents[0].shouldBeInstanceOf<TokenUsageEvent>()
            stored.estimatedCostUsd shouldBe BigDecimal.ZERO
        }

        @Test
        fun `keeps original event when cost calculation throws`() {
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
            Thread.sleep(200)
            writer.stop()

            storedEvents.size shouldBe 1
            val stored = storedEvents[0].shouldBeInstanceOf<TokenUsageEvent>()
            stored.estimatedCostUsd shouldBe BigDecimal.ZERO
        }

        @Test
        fun `passes through non-token events unchanged`() {
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
            Thread.sleep(200)
            writer.stop()

            storedEvents.size shouldBe 1
            storedEvents[0].shouldBeInstanceOf<AgentExecutionEvent>()

            verify(exactly = 0) { costCalculator.calculate(any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class HealthTracking {

        @Test
        fun `records write count and latency on successful flush`() {
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
            Thread.sleep(200)
            writer.stop()

            healthMonitor.writtenTotal.get() shouldBe 1
        }

        @Test
        fun `records write error on store failure`() {
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
            Thread.sleep(200)
            writer.stop()

            healthMonitor.writeErrorsTotal.get() shouldBe 1
        }
    }

    @Nested
    inner class FlushSerialization {

        @Test
        fun `concurrent flush calls do not cause duplicate events`() {
            every {
                costCalculator.calculate(any(), any(), any(), any(), any(), any(), any())
            } returns BigDecimal("0.001")

            // Publish 100 events
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
            Thread.sleep(500)
            writer.stop()

            // All events should be stored exactly once (no duplicates)
            storedEvents.size shouldBe 100
        }
    }

    @Nested
    inner class Lifecycle {

        @Test
        fun `start is idempotent`() {
            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                healthMonitor = healthMonitor
            )
            writer.start()
            writer.start() // second call should be no-op
            writer.stop()
        }

        @Test
        fun `stop is idempotent`() {
            val writer = MetricWriter(
                ringBuffer = ringBuffer,
                store = store,
                costCalculator = costCalculator,
                healthMonitor = healthMonitor
            )
            writer.start()
            writer.stop()
            writer.stop() // second call should be no-op
        }

        @Test
        fun `flushes remaining events on stop`() {
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

            // Publish events AFTER start so they're in buffer
            writer.start()
            ringBuffer.publish(tokenEvent())
            ringBuffer.publish(executionEvent())

            // Stop should trigger final flush
            writer.stop()

            storedEvents.size shouldBe 2
        }
    }
}
