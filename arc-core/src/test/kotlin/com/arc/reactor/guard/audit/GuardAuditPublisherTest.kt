package com.arc.reactor.guard.audit

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.impl.GuardPipeline
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class GuardAuditPublisherTest {

    // Test implementation that captures events
    class CapturingAuditPublisher : GuardAuditPublisher {
        val events = CopyOnWriteArrayList<AuditEvent>()

        override fun publish(
            command: GuardCommand,
            stage: String,
            result: String,
            reason: String?,
            category: String?,
            stageLatencyMs: Long,
            pipelineLatencyMs: Long
        ) {
            events.add(AuditEvent(stage, result, reason, category, stageLatencyMs, pipelineLatencyMs))
        }
    }

    data class AuditEvent(
        val stage: String,
        val result: String,
        val reason: String?,
        val category: String?,
        val stageLatencyMs: Long,
        val pipelineLatencyMs: Long
    )

    @Nested
    inner class AuditOnRejection {

        @Test
        fun `audit event published on rejection with correct stage`() = runBlocking {
            val publisher = CapturingAuditPublisher()
            val rejectingStage = object : GuardStage {
                override val stageName = "TestReject"
                override val order = 1
                override suspend fun check(command: GuardCommand): GuardResult =
                    GuardResult.Rejected("bad input", RejectionCategory.PROMPT_INJECTION)
            }
            val pipeline = GuardPipeline(listOf(rejectingStage), auditPublisher = publisher)

            pipeline.guard(GuardCommand(userId = "user-1", text = "test"))

            assertTrue(publisher.events.isNotEmpty(), "Should publish audit events")
            val rejectEvent = publisher.events.find { it.result == "rejected" }
            assertNotNull(rejectEvent, "Should have a rejected event")
            assertEquals("TestReject", rejectEvent!!.stage)
            assertEquals("bad input", rejectEvent.reason)
            assertEquals("PROMPT_INJECTION", rejectEvent.category,
                "Category should be the RejectionCategory enum name")
            assertTrue(rejectEvent.stageLatencyMs >= 0,
                "Stage latency should be non-negative")
            assertTrue(rejectEvent.pipelineLatencyMs >= 0,
                "Pipeline latency should be non-negative")
        }
    }

    @Nested
    inner class AuditOnAllowed {

        @Test
        fun `audit event published on pipeline complete`() = runBlocking {
            val publisher = CapturingAuditPublisher()
            val passingStage = object : GuardStage {
                override val stageName = "TestPass"
                override val order = 1
                override suspend fun check(command: GuardCommand) = GuardResult.Allowed.DEFAULT
            }
            val pipeline = GuardPipeline(listOf(passingStage), auditPublisher = publisher)

            pipeline.guard(GuardCommand(userId = "user-1", text = "hello"))

            // Should have per-stage + pipeline-complete events
            val pipelineEvent = publisher.events.find { it.stage == "pipeline" }
            assertNotNull(pipelineEvent, "Should have pipeline-complete event")
            assertEquals("allowed", pipelineEvent!!.result)
        }
    }

    @Nested
    inner class AuditOnError {

        @Test
        fun `audit event published on stage error`() = runBlocking {
            val publisher = CapturingAuditPublisher()
            val errorStage = object : GuardStage {
                override val stageName = "ErrorStage"
                override val order = 1
                override suspend fun check(command: GuardCommand): GuardResult =
                    throw RuntimeException("stage exploded")
            }
            val pipeline = GuardPipeline(listOf(errorStage), auditPublisher = publisher)

            pipeline.guard(GuardCommand(userId = "user-1", text = "test"))

            val errorEvent = publisher.events.find { it.result == "error" }
            assertNotNull(errorEvent, "Should have an error event")
            assertEquals("ErrorStage", errorEvent!!.stage)
            assertEquals("stage exploded", errorEvent.reason)
        }
    }

    @Nested
    inner class LatencyTracking {

        @Test
        fun `per-stage latency is recorded`() = runBlocking {
            val publisher = CapturingAuditPublisher()
            val slowStage = object : GuardStage {
                override val stageName = "SlowStage"
                override val order = 1
                override suspend fun check(command: GuardCommand): GuardResult {
                    Thread.sleep(10) // Simulate some work
                    return GuardResult.Allowed.DEFAULT
                }
            }
            val pipeline = GuardPipeline(listOf(slowStage), auditPublisher = publisher)

            pipeline.guard(GuardCommand(userId = "user-1", text = "test"))

            val stageEvent = publisher.events.find { it.stage == "SlowStage" }
            assertNotNull(stageEvent, "Should have stage event")
            assertTrue(stageEvent!!.stageLatencyMs >= 5,
                "Stage latency should reflect actual execution time, got: ${stageEvent.stageLatencyMs}ms")
        }

        @Test
        fun `pipeline latency accumulates across stages`() = runBlocking {
            val publisher = CapturingAuditPublisher()
            val stage1 = object : GuardStage {
                override val stageName = "Stage1"
                override val order = 1
                override suspend fun check(command: GuardCommand): GuardResult {
                    Thread.sleep(5)
                    return GuardResult.Allowed.DEFAULT
                }
            }
            val stage2 = object : GuardStage {
                override val stageName = "Stage2"
                override val order = 2
                override suspend fun check(command: GuardCommand): GuardResult {
                    Thread.sleep(5)
                    return GuardResult.Allowed.DEFAULT
                }
            }
            val pipeline = GuardPipeline(listOf(stage1, stage2), auditPublisher = publisher)

            pipeline.guard(GuardCommand(userId = "user-1", text = "test"))

            val pipelineEvent = publisher.events.find { it.stage == "pipeline" }
            assertNotNull(pipelineEvent, "Should have pipeline-complete event")
            assertTrue(pipelineEvent!!.pipelineLatencyMs >= 5,
                "Pipeline latency should accumulate, got: ${pipelineEvent.pipelineLatencyMs}ms")
        }
    }

    @Nested
    inner class NullPublisher {

        @Test
        fun `pipeline works without audit publisher`() = runBlocking {
            val passingStage = object : GuardStage {
                override val stageName = "NoAudit"
                override val order = 1
                override suspend fun check(command: GuardCommand) = GuardResult.Allowed.DEFAULT
            }
            val pipeline = GuardPipeline(listOf(passingStage), auditPublisher = null)

            val result = pipeline.guard(GuardCommand(userId = "user-1", text = "test"))
            assertInstanceOf(GuardResult.Allowed::class.java, result,
                "Pipeline should work fine without audit publisher")
        }
    }
}
