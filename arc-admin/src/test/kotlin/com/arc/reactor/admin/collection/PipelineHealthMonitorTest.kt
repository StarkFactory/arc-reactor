package com.arc.reactor.admin.collection

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PipelineHealthMonitorTest {

    private lateinit var monitor: PipelineHealthMonitor

    @BeforeEach
    fun setUp() {
        monitor = PipelineHealthMonitor()
    }

    @Nested
    inner class RecordWrite {

        @Test
        fun `increments writtenTotal by count`() {
            monitor.recordWrite(5, 10L)
            monitor.writtenTotal.get() shouldBe 5

            monitor.recordWrite(3, 15L)
            monitor.writtenTotal.get() shouldBe 8
        }

        @Test
        fun `sets writeLatencyMs to latest value`() {
            monitor.recordWrite(1, 10L)
            monitor.writeLatencyMs.get() shouldBe 10

            monitor.recordWrite(1, 25L)
            monitor.writeLatencyMs.get() shouldBe 25
        }
    }

    @Nested
    inner class RecordDrop {

        @Test
        fun `increments droppedTotal by count`() {
            monitor.recordDrop(3)
            monitor.droppedTotal.get() shouldBe 3

            monitor.recordDrop(7)
            monitor.droppedTotal.get() shouldBe 10
        }
    }

    @Nested
    inner class RecordWriteError {

        @Test
        fun `increments writeErrorsTotal`() {
            monitor.recordWriteError()
            monitor.writeErrorsTotal.get() shouldBe 1

            monitor.recordWriteError()
            monitor.writeErrorsTotal.get() shouldBe 2
        }
    }

    @Nested
    inner class BufferUsage {

        @Test
        fun `updates bufferUsagePercent`() {
            monitor.updateBufferUsage(45.5)
            monitor.bufferUsagePercent shouldBe 45.5

            monitor.updateBufferUsage(80.0)
            monitor.bufferUsagePercent shouldBe 80.0
        }
    }

    @Nested
    inner class Snapshot {

        @Test
        fun `returns consistent snapshot of all metrics`() {
            monitor.recordWrite(10, 50L)
            monitor.recordDrop(2)
            monitor.recordWriteError()
            monitor.updateBufferUsage(33.3)

            val snapshot = monitor.snapshot()

            snapshot.writtenTotal shouldBe 10
            snapshot.writeLatencyMs shouldBe 50
            snapshot.droppedTotal shouldBe 2
            snapshot.writeErrorsTotal shouldBe 1
            snapshot.bufferUsagePercent shouldBe 33.3
        }

        @Test
        fun `returns zero values for fresh monitor`() {
            val snapshot = monitor.snapshot()

            snapshot.writtenTotal shouldBe 0
            snapshot.writeLatencyMs shouldBe 0
            snapshot.droppedTotal shouldBe 0
            snapshot.writeErrorsTotal shouldBe 0
            snapshot.bufferUsagePercent shouldBe 0.0
        }
    }
}
