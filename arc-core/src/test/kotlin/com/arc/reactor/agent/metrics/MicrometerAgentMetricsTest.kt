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

        metrics.recordOutputGuardAction("pipeline", "modified", "removed unsafe claim")
        metrics.recordBoundaryViolation("output_too_short", "fail", 200, 12)
        metrics.recordUnverifiedResponse(mapOf("channel" to "slack"))

        val events = metrics.recentTrustEvents(5)

        assertEquals(3, events.size, "Recent trust events should retain output guard, boundary, and source issues")
        assertEquals("unverified_response", events[0].type, "Newest event should be returned first")
        assertEquals("WARN", events[0].severity, "Unverified responses should be marked WARN")
        assertEquals("boundary_violation", events[1].type, "Boundary violations should be recorded")
        assertTrue(events[2].reason?.contains("unsafe", ignoreCase = true) == true,
            "Output guard event should preserve the rejection/modification reason")
    }
}
