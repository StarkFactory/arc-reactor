package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.AgentExecutionEvent
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MetricRingBufferTest {

    private lateinit var buffer: MetricRingBuffer

    private fun testEvent(runId: String = "run-1") = AgentExecutionEvent(
        tenantId = "tenant-1",
        runId = runId,
        success = true
    )

    @BeforeEach
    fun setUp() {
        buffer = MetricRingBuffer(64)
    }

    @Nested
    inner class BasicOperations {

        @Test
        fun `should publish and drain single event`() {
            val published = buffer.publish(testEvent())
            published shouldBe true

            buffer.size() shouldBe 1

            val events = buffer.drain(10)
            events.size shouldBe 1
            buffer.size() shouldBe 0
        }

        @Test
        fun `should drain up to maxBatch`() {
            repeat(10) { buffer.publish(testEvent("run-$it")) }

            val batch = buffer.drain(5)
            batch.size shouldBe 5
            buffer.size() shouldBe 5
        }

        @Test
        fun `should return empty list when buffer is empty`() {
            val events = buffer.drain(10)
            events.size shouldBe 0
        }
    }

    @Nested
    inner class Capacity {

        @Test
        fun `should drop events when full`() {
            // Fill to capacity (64 = next power of 2)
            repeat(64) { buffer.publish(testEvent("run-$it")) }

            // This should be dropped
            val published = buffer.publish(testEvent("overflow"))
            published shouldBe false
            buffer.droppedCount.get() shouldBe 1
        }

        @Test
        fun `should use highest power of 2`() {
            val buf = MetricRingBuffer(100) // highestOneBit(100) = 64
            buf.capacity() shouldBe 64
        }

        @Test
        fun `should report usage percent`() {
            repeat(32) { buffer.publish(testEvent("run-$it")) }
            buffer.usagePercent() shouldBe 50.0
        }
    }

    @Nested
    inner class Concurrency {

        @Test
        fun `should handle concurrent producers`() {
            val largeBuffer = MetricRingBuffer(8192)
            val threadCount = 8
            val eventsPerThread = 500
            val latch = CountDownLatch(threadCount)
            val publishedCount = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(threadCount)

            repeat(threadCount) { threadIdx ->
                executor.submit {
                    try {
                        repeat(eventsPerThread) { i ->
                            if (largeBuffer.publish(testEvent("t$threadIdx-$i"))) {
                                publishedCount.incrementAndGet()
                            }
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await()
            executor.shutdown()

            val total = threadCount * eventsPerThread
            val published = publishedCount.get()
            val dropped = largeBuffer.droppedCount.get()

            // CAS-based: published + dropped must equal total
            (published + dropped).toInt() shouldBe total

            // Drain all
            val drained = mutableListOf<com.arc.reactor.admin.model.MetricEvent>()
            while (true) {
                val batch = largeBuffer.drain(1000)
                if (batch.isEmpty()) break
                drained.addAll(batch)
            }

            drained.size shouldBe published
        }
    }
}
