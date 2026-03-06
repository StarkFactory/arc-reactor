package com.arc.reactor.agent.metrics

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MicrometerAgentMetricsTest {

    @Test
    fun `records execution output guard and unverified counters`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAgentMetrics(registry)

        metrics.recordExecution(
            AgentResult.failure(
                errorMessage = "blocked",
                errorCode = AgentErrorCode.OUTPUT_GUARD_REJECTED,
                durationMs = 12
            )
        )
        metrics.recordOutputGuardAction("pipeline", "rejected", "blocked")
        metrics.recordUnverifiedResponse(mapOf("channel" to "web"))

        assertEquals(1.0, registry.get("arc.agent.executions").counter().count(), 0.0)
        assertEquals(1.0, registry.get("arc.agent.errors").counter().count(), 0.0)
        assertEquals(1.0, registry.get("arc.agent.output.guard.actions").counter().count(), 0.0)
        assertEquals(1.0, registry.get("arc.agent.responses.unverified").counter().count(), 0.0)
    }

    @Test
    fun `keeps recent trust events for dashboard drill-down`() {
        val metrics = MicrometerAgentMetrics(SimpleMeterRegistry())

        metrics.recordOutputGuardAction(
            "pipeline",
            "modified",
            "removed unsafe claim",
            mapOf("runId" to "run-1", "userId" to "eddy", "queryPreview" to "Summarize release blockers")
        )
        metrics.recordBoundaryViolation(
            "output_too_short",
            "fail",
            200,
            12,
            mapOf("runId" to "run-2", "userId" to "eddy")
        )
        metrics.recordUnverifiedResponse(
            mapOf("channel" to "slack", "runId" to "run-3", "userId" to "eddy", "queryPreview" to "What is our policy?")
        )

        val events = metrics.recentTrustEvents(5)

        assertEquals(3, events.size, "Recent trust events should retain output guard, boundary, and source issues")
        assertEquals("unverified_response", events[0].type, "Newest event should be returned first")
        assertEquals("WARN", events[0].severity, "Unverified responses should be marked WARN")
        assertEquals("run-3", events[0].runId, "Recent unverified event should include run id")
        assertEquals("What is our policy?", events[0].queryPreview, "Query preview should be retained for drill-down")
        assertEquals("boundary_violation", events[1].type, "Boundary violations should be recorded")
        assertEquals("eddy", events[1].userId, "Boundary event should retain operator context")
        assertTrue(events[2].reason?.contains("unsafe", ignoreCase = true) == true,
            "Output guard event should preserve the rejection/modification reason")
    }
}
