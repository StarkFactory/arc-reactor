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
            mapOf("queryCluster" to "c1a22afdfaf0", "queryLabel" to "Prompt cluster c1a22afdfaf0")
        )
        metrics.recordBoundaryViolation(
            "output_too_short",
            "fail",
            200,
            12,
            mapOf("queryCluster" to "bb19e5f8445e", "queryLabel" to "Prompt cluster bb19e5f8445e")
        )
        metrics.recordUnverifiedResponse(
            mapOf(
                "channel" to "slack",
                "queryCluster" to "09d2549d30cb",
                "queryLabel" to "Question cluster 09d2549d30cb"
            )
        )

        val events = metrics.recentTrustEvents(5)

        assertEquals(3, events.size, "Recent trust events should retain output guard, boundary, and source issues")
        assertEquals("unverified_response", events[0].type, "Newest event should be returned first")
        assertEquals("WARN", events[0].severity, "Unverified responses should be marked WARN")
        assertEquals("09d2549d30cb", events[0].queryCluster, "Recent unverified event should expose only a cluster id")
        assertEquals(
            "Question cluster 09d2549d30cb",
            events[0].queryLabel,
            "Query labels should be redacted for dashboard drill-down"
        )
        assertEquals("boundary_violation", events[1].type, "Boundary violations should be recorded")
        assertEquals(
            "Prompt cluster bb19e5f8445e",
            events[1].queryLabel,
            "Boundary events should preserve redacted query clusters"
        )
        assertTrue(events[2].reason?.contains("unsafe", ignoreCase = true) == true,
            "Output guard event should preserve the rejection/modification reason")
    }

    @Test
    fun `tracks response value summary and top missing questions`() {
        val metrics = MicrometerAgentMetrics(SimpleMeterRegistry())

        metrics.recordResponseObservation(
            mapOf(
                "grounded" to true,
                "answerMode" to "knowledge",
                "deliveryMode" to "interactive",
                "toolFamily" to "confluence",
                "queryPreview" to "What is the on-call policy?"
            )
        )
        metrics.recordResponseObservation(
            mapOf(
                "grounded" to true,
                "answerMode" to "operational",
                "deliveryMode" to "scheduled",
                "toolFamily" to "work",
                "queryPreview" to "Morning briefing"
            )
        )
        metrics.recordResponseObservation(
            mapOf(
                "grounded" to false,
                "answerMode" to "unknown",
                "deliveryMode" to "interactive",
                "toolFamily" to "none",
                "queryCluster" to "f1e6a063a8d0",
                "queryLabel" to "Question cluster f1e6a063a8d0",
                "blockReason" to "unverified_sources"
            )
        )
        metrics.recordResponseObservation(
            mapOf(
                "grounded" to false,
                "answerMode" to "unknown",
                "deliveryMode" to "interactive",
                "toolFamily" to "none",
                "queryCluster" to "f1e6a063a8d0",
                "queryLabel" to "Question cluster f1e6a063a8d0",
                "blockReason" to "unverified_sources"
            )
        )

        val summary = metrics.responseValueSummary()
        val missing = metrics.topMissingQueries(5)

        assertEquals(4L, summary.observedResponses, "Observed response count should include every final response")
        assertEquals(2L, summary.groundedResponses, "Grounded response count should track verified replies")
        assertEquals(2L, summary.blockedResponses, "Blocked responses should track source failures")
        assertEquals(3L, summary.interactiveResponses, "Interactive responses should be counted separately")
        assertEquals(1L, summary.scheduledResponses, "Scheduled responses should be counted separately")
        assertEquals(1L, summary.answerModeCounts["knowledge"], "Knowledge answer mode should be tallied")
        assertEquals(4L, summary.channelCounts["unknown"], "Channel counts should keep missing channel traffic visible")
        assertEquals(1L, summary.toolFamilyCounts["work"], "Tool family counts should track work lane usage")
        assertEquals(2L, summary.laneSummaries.first { it.answerMode == "unknown" }.blockedResponses,
            "Lane summary should track blocked responses for the answer mode")
        assertEquals(1L, summary.laneSummaries.first { it.answerMode == "knowledge" }.groundedResponses,
            "Lane summary should track grounded responses for the answer mode")
        assertEquals(1, missing.size, "Repeated blocked queries should be aggregated after normalization")
        assertEquals(2L, missing[0].count, "Top missing query should keep repeated count")
        assertEquals("f1e6a063a8d0", missing[0].queryCluster, "Top missing query should expose only a redacted cluster id")
        assertEquals(
            "Question cluster f1e6a063a8d0",
            missing[0].queryLabel,
            "Top missing query should expose a redacted label"
        )
    }
}
