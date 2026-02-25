package com.arc.reactor.admin.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AdminPropertiesTest {

    @Test
    fun `should have sensible defaults`() {
        val props = AdminProperties()

        props.enabled shouldBe false
        props.timescaleEnabled shouldBe true
        props.tracing.enabled shouldBe true
        props.tracing.otlp.enabled shouldBe false
        props.collection.ringBufferSize shouldBe 8192
        props.collection.flushIntervalMs shouldBe 1000
        props.collection.batchSize shouldBe 1000
        props.collection.writerThreads shouldBe 1
        props.retention.rawDays shouldBe 90
        props.retention.auditYears shouldBe 7
        props.retention.compressionAfterDays shouldBe 7
        props.slo.defaultAvailability shouldBe 0.995
        props.slo.defaultLatencyP99Ms shouldBe 10000
        props.scaling.mode shouldBe ScalingMode.DIRECT_WRITE
    }

    @Test
    fun `should allow customization`() {
        val props = AdminProperties(
            enabled = true,
            collection = CollectionProperties(ringBufferSize = 16384, writerThreads = 5),
            retention = RetentionProperties(rawDays = 180),
            slo = SloProperties(defaultAvailability = 0.999)
        )

        props.enabled shouldBe true
        props.collection.ringBufferSize shouldBe 16384
        props.collection.writerThreads shouldBe 5
        props.retention.rawDays shouldBe 180
        props.slo.defaultAvailability shouldBe 0.999
    }
}
