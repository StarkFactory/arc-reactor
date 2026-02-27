package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.GuardEvent
import com.arc.reactor.admin.model.MetricEvent
import com.arc.reactor.guard.model.GuardCommand
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MetricGuardAuditPublisherTest {

    private fun createPublisher(bufferSize: Int = 64): Triple<MetricGuardAuditPublisher, MetricRingBuffer, PipelineHealthMonitor> {
        val ringBuffer = MetricRingBuffer(bufferSize)
        val healthMonitor = PipelineHealthMonitor()
        val publisher = MetricGuardAuditPublisher(ringBuffer, healthMonitor)
        return Triple(publisher, ringBuffer, healthMonitor)
    }

    private fun MetricRingBuffer.drainAll(): List<MetricEvent> = drain(size().coerceAtLeast(1))

    @Nested
    inner class PublishRejection {

        @Test
        fun `rejection event has correct stage and category`() {
            val (publisher, ringBuffer, _) = createPublisher()

            publisher.publish(
                command = GuardCommand(userId = "user-1", text = "bad input"),
                stage = "InjectionDetection",
                result = "rejected",
                reason = "Suspicious pattern detected",
                category = "PROMPT_INJECTION",
                stageLatencyMs = 5,
                pipelineLatencyMs = 10
            )

            val events = ringBuffer.drainAll()
            assertEquals(1, events.size, "Should publish one event")
            val event = events[0] as GuardEvent
            assertEquals("InjectionDetection", event.stage)
            assertEquals("PROMPT_INJECTION", event.category)
            assertEquals("rejected", event.action)
            assertEquals("Suspicious pattern detected", event.reasonDetail)
            assertEquals("PROMPT_INJECTION", event.reasonClass)
            assertEquals(5, event.stageLatencyMs)
            assertEquals(10, event.pipelineLatencyMs)
        }

        @Test
        fun `rejection without category falls back to stage-derived category`() {
            val (publisher, ringBuffer, _) = createPublisher()

            publisher.publish(
                command = GuardCommand(userId = "user-1", text = "test"),
                stage = "RateLimit",
                result = "rejected",
                reason = "Too many requests",
                category = null,
                stageLatencyMs = 1,
                pipelineLatencyMs = 1
            )

            val events = ringBuffer.drainAll()
            val event = events[0] as GuardEvent
            assertEquals("rate_limit", event.category,
                "Should fall back to classifyReason when category is null")
        }
    }

    @Nested
    inner class PublishAllowed {

        @Test
        fun `allowed event has correct action`() {
            val (publisher, ringBuffer, _) = createPublisher()

            publisher.publish(
                command = GuardCommand(userId = "user-1", text = "hello"),
                stage = "pipeline",
                result = "allowed",
                reason = null,
                stageLatencyMs = 0,
                pipelineLatencyMs = 15
            )

            val events = ringBuffer.drainAll()
            assertEquals(1, events.size, "Should publish one event")
            val event = events[0] as GuardEvent
            assertEquals("pipeline", event.stage)
            assertEquals("allowed", event.action)
            assertNull(event.reasonClass, "Allowed event should have null reasonClass")
            assertNull(event.reasonDetail, "Allowed event should have null reasonDetail")
        }
    }

    @Nested
    inner class InputHashing {

        @Test
        fun `input text is SHA-256 hashed, not stored raw`() {
            val (publisher, ringBuffer, _) = createPublisher()
            val rawText = "sensitive user input that should not be stored"

            publisher.publish(
                command = GuardCommand(userId = "user-1", text = rawText),
                stage = "InputValidation",
                result = "allowed",
                reason = null,
                stageLatencyMs = 1,
                pipelineLatencyMs = 1
            )

            val events = ringBuffer.drainAll()
            val event = events[0] as GuardEvent
            assertNotNull(event.inputHash, "Input hash should not be null")
            val hash = event.inputHash!!
            assertFalse(hash.contains("sensitive"),
                "Input hash should not contain raw text")
            assertEquals(64, hash.length,
                "SHA-256 hex should be 64 characters")
        }

        @Test
        fun `same input produces same hash`() {
            val (publisher, ringBuffer, _) = createPublisher()
            val text = "deterministic test"

            publisher.publish(
                command = GuardCommand(userId = "user-1", text = text),
                stage = "Stage1", result = "allowed", reason = null,
                stageLatencyMs = 0, pipelineLatencyMs = 0
            )
            publisher.publish(
                command = GuardCommand(userId = "user-2", text = text),
                stage = "Stage2", result = "allowed", reason = null,
                stageLatencyMs = 0, pipelineLatencyMs = 0
            )

            val events = ringBuffer.drainAll()
            assertEquals((events[0] as GuardEvent).inputHash, (events[1] as GuardEvent).inputHash,
                "Same text should produce same hash")
        }
    }

    @Nested
    inner class MetadataExtraction {

        @Test
        fun `tenant, session, and request IDs extracted from metadata`() {
            val (publisher, ringBuffer, _) = createPublisher()

            publisher.publish(
                command = GuardCommand(
                    userId = "user-1",
                    text = "test",
                    channel = "slack",
                    metadata = mapOf(
                        "tenantId" to "tenant-abc",
                        "sessionId" to "session-xyz",
                        "requestId" to "req-123"
                    )
                ),
                stage = "InputValidation",
                result = "allowed",
                reason = null,
                stageLatencyMs = 1,
                pipelineLatencyMs = 1
            )

            val events = ringBuffer.drainAll()
            val event = events[0] as GuardEvent
            assertEquals("tenant-abc", event.tenantId)
            assertEquals("session-xyz", event.sessionId)
            assertEquals("req-123", event.requestId)
            assertEquals("slack", event.channel)
            assertEquals("user-1", event.userId)
        }

        @Test
        fun `missing metadata defaults gracefully`() {
            val (publisher, ringBuffer, _) = createPublisher()

            publisher.publish(
                command = GuardCommand(userId = "user-1", text = "test"),
                stage = "InputValidation",
                result = "allowed",
                reason = null,
                stageLatencyMs = 0,
                pipelineLatencyMs = 0
            )

            val events = ringBuffer.drainAll()
            val event = events[0] as GuardEvent
            assertEquals("default", event.tenantId,
                "Missing tenantId should default to 'default'")
            assertNull(event.sessionId, "Missing sessionId should be null")
            assertNull(event.requestId, "Missing requestId should be null")
        }
    }

    @Nested
    inner class BufferFull {

        @Test
        fun `drop recorded when ring buffer is full`() {
            val (publisher, _, healthMonitor) = createPublisher(bufferSize = 64)

            // Fill the buffer completely
            repeat(70) { i ->
                publisher.publish(
                    command = GuardCommand(userId = "user-$i", text = "test-$i"),
                    stage = "Stage",
                    result = "allowed",
                    reason = null,
                    stageLatencyMs = 0,
                    pipelineLatencyMs = 0
                )
            }

            assertTrue(healthMonitor.droppedTotal.get() > 0,
                "Should record drops when buffer is full, got: ${healthMonitor.droppedTotal.get()}")
        }
    }

    @Nested
    inner class ReasonTruncation {

        @Test
        fun `long reason is truncated to 500 chars`() {
            val (publisher, ringBuffer, _) = createPublisher()
            val longReason = "x".repeat(1000)

            publisher.publish(
                command = GuardCommand(userId = "user-1", text = "test"),
                stage = "TestStage",
                result = "rejected",
                reason = longReason,
                category = "PROMPT_INJECTION",
                stageLatencyMs = 0,
                pipelineLatencyMs = 0
            )

            val events = ringBuffer.drainAll()
            val event = events[0] as GuardEvent
            assertEquals(500, event.reasonDetail?.length,
                "Reason should be truncated to 500 chars")
        }
    }
}
