package com.arc.reactor.agent.metrics

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Micrometer 기반 AgentMetrics에 대한 테스트.
 *
 * Micrometer 메트릭 기록 동작을 검증합니다.
 */
class MicrometerAgentMetricsTest {

    @Test
    fun `execution output guard and unverified counters를 기록한다`() {
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
    fun `keeps은(는) recent trust events for dashboard drill-down`() {
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
    fun `response value summary and top missing questions를 추적한다`() {
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

    /**
     * R341 regression: `recordUnverifiedResponse`의 `channel` 태그가 budget cache로 bounded
     * cardinality 제한되는지 검증. `MAX_UNVERIFIED_CHANNEL_TAGS`(128) 이상의 고유 channel이
     * 유입되면 상한 초과 값은 `OVERFLOW_CHANNEL_TAG`("other")로 폴백되어야 한다.
     *
     * SimpleMeterRegistry에서 `Counter`의 `channel` 태그별 시계열 수를 세어 budget 상한 근처에
     * 수렴하는지 확인한다.
     */
    @Test
    fun `R341 recordUnverifiedResponse는 channel 태그 카디널리티를 bounded 값으로 제한해야 한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAgentMetrics(registry)
        val budget = MicrometerAgentMetrics.MAX_UNVERIFIED_CHANNEL_TAGS.toInt()
        val totalUnique = budget + 50  // budget 초과

        // budget + 50개의 고유 channel 주입
        repeat(totalUnique) { idx ->
            metrics.recordUnverifiedResponse(mapOf("channel" to "ch-$idx"))
        }
        // overflow 폴백도 호출되도록 한 번 더
        metrics.recordUnverifiedResponse(mapOf("channel" to "ch-overflow-test"))

        // Counter의 고유 channel 태그 수 조회
        val distinctTags = registry.find("arc.agent.responses.unverified").counters()
            .map { it.id.getTag("channel") }
            .toSet()

        // budget + overflow 태그("other") + 잠재 "unknown" 정도로 상한이 있어야 한다
        // budget=128이면 distinctTags는 최대 128 + 1(other) = 129 정도
        assertTrue(distinctTags.size <= budget + 2) {
            "R341: distinct channel 태그 수는 budget($budget) + 상수 폴백 이하여야 한다. " +
                "실제=${distinctTags.size}, 주입=$totalUnique"
        }
        assertTrue(distinctTags.contains("other")) {
            "R341: budget 초과 시 'other' 폴백 태그가 등록되어야 한다. 실제 태그=$distinctTags"
        }
    }

    @Test
    fun `R341 recordUnverifiedResponse는 null_blank channel을 unknown으로 폴백해야 한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAgentMetrics(registry)

        metrics.recordUnverifiedResponse(emptyMap())
        metrics.recordUnverifiedResponse(mapOf("channel" to ""))
        metrics.recordUnverifiedResponse(mapOf("channel" to "   "))

        val unknownCounter = registry.find("arc.agent.responses.unverified")
            .tag("channel", "unknown")
            .counter()
        assertEquals(3.0, unknownCounter!!.count(), 0.0) {
            "R341: null/empty/blank channel은 'unknown' 태그로 기록되어야 한다"
        }
    }

    /**
     * R340 regression: 병렬 스레드에서 `trackMissingQuery`가 호출될 때 count는 정확히
     * 누적되고 `lastOccurredAt`이 stale timestamp로 고정되지 않아야 한다. 이전 구현은
     * `count.incrementAndGet()`과 `lastOccurredAt = Instant.now()` 사이의 순서가 뒤바뀌어
     * 예전 시각이 최종 값으로 남을 수 있었다. R340은 `AtomicReference.updateAndGet`으로
     * "가장 최근 시각"만 유지한다.
     *
     * race 자체를 직접 트리거하기는 어렵지만, 병렬 호출 후 (1) count 정확도 + (2)
     * lastOccurredAt이 "최근 10초 이내"에 수렴하는지 cross-verify한다.
     */
    @Test
    fun `R340 병렬 trackMissingQuery에서 lastOccurredAt이 최신 값으로 수렴해야 한다`() {
        val metrics = MicrometerAgentMetrics(SimpleMeterRegistry())
        val callsPerThread = 500
        val threads = 4
        val startAt = Instant.now()

        runBlocking {
            withContext(Dispatchers.Default) {
                coroutineScope {
                    (1..threads).map {
                        async {
                            repeat(callsPerThread) {
                                metrics.recordResponseObservation(
                                    mapOf(
                                        "grounded" to false,
                                        "answerMode" to "unknown",
                                        "deliveryMode" to "interactive",
                                        "toolFamily" to "none",
                                        "queryCluster" to "parallel-race-r340",
                                        "queryLabel" to "Parallel race R340",
                                        "blockReason" to "unverified_sources"
                                    )
                                )
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        val endAt = Instant.now()
        val missing = metrics.topMissingQueries(5)
        assertEquals(1, missing.size) {
            "R340: 병렬 호출이 모두 같은 cluster로 수렴해야 한다"
        }
        assertEquals((threads * callsPerThread).toLong(), missing[0].count) {
            "R340: count는 원자적으로 ${threads * callsPerThread}까지 정확히 누적되어야 한다"
        }
        val lastOccurredAt = missing[0].lastOccurredAt
        assertTrue(!lastOccurredAt.isBefore(startAt)) {
            "R340: lastOccurredAt은 테스트 시작 시각 이후여야 한다 (stale 아님). " +
                "start=$startAt, last=$lastOccurredAt"
        }
        assertTrue(!lastOccurredAt.isAfter(endAt)) {
            "R340: lastOccurredAt은 테스트 종료 시각 이전이어야 한다 (미래 아님). " +
                "end=$endAt, last=$lastOccurredAt"
        }
    }

    @Test
    fun `stage latency timer를 기록한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAgentMetrics(registry)

        metrics.recordStageLatency("agent_loop", 42, mapOf("channel" to "web"))

        val timer = registry.get("arc.agent.stage.duration")
            .tag("stage", "agent_loop")
            .tag("channel", "web")
            .timer()
        assertEquals(1L, timer.count(), "Stage timer should record a single sample")
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 42.0) {
            "Stage timer should record the supplied latency"
        }
    }

    @Test
    fun `LLM latency timer with percentiles를 기록한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAgentMetrics(registry)

        metrics.recordLlmLatency("gemini-2.5-flash", 150)
        metrics.recordLlmLatency("gemini-2.5-flash", 250)

        val timer = registry.get("arc.agent.llm.latency")
            .tag("model", "gemini-2.5-flash")
            .timer()
        assertEquals(2L, timer.count(), "LLM latency timer should record two samples")
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 400.0) {
            "LLM latency timer should accumulate total duration"
        }
    }

    @Test
    fun `tool output size and truncation counter를 기록한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAgentMetrics(registry)

        metrics.recordToolOutputSize("web_search", 12000, false)
        metrics.recordToolOutputSize("web_search", 50000, true)

        val summary = registry.get("arc.agent.tool.output.size")
            .tag("tool", "web_search")
            .summary()
        assertEquals(2L, summary.count(), "Tool output size should record two samples")
        assertEquals(62000.0, summary.totalAmount(), "Tool output size should sum byte counts")

        val truncated = registry.get("arc.agent.tool.output.truncated")
            .tag("tool", "web_search")
            .counter()
        assertEquals(1.0, truncated.count(), 0.0, "Truncation counter should increment only when truncated=true")
    }

    @Test
    fun `does not increment truncation counter when output은(는) not truncated이다`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAgentMetrics(registry)

        metrics.recordToolOutputSize("calculator", 500, false)

        val summary = registry.get("arc.agent.tool.output.size")
            .tag("tool", "calculator")
            .summary()
        assertEquals(1L, summary.count(), "Tool output size should record the sample")

        val truncatedMeters = registry.find("arc.agent.tool.output.truncated")
            .tag("tool", "calculator")
            .counter()
        assertTrue(truncatedMeters == null || truncatedMeters.count() == 0.0) {
            "Truncation counter should not be created when output is never truncated"
        }
    }

    @Test
    fun `request cost를 모델별 테넌트별로 기록한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAgentMetrics(registry)

        metrics.recordRequestCost(0.025, "gpt-4o", mapOf("tenantId" to "tenant-a"))
        metrics.recordRequestCost(0.010, "gpt-4o", mapOf("tenantId" to "tenant-a"))
        metrics.recordRequestCost(0.005, "gemini-2.5-flash", mapOf("tenantId" to "tenant-b"))

        val costSummary = registry.get("arc.agent.request.cost")
            .tag("model", "gpt-4o")
            .tag("tenantId", "tenant-a")
            .summary()
        assertEquals(2L, costSummary.count(), "비용 분포에 두 건이 기록되어야 한다")
        assertEquals(0.035, costSummary.totalAmount(), 0.001, "비용 합계가 정확해야 한다")

        val totalCounter = registry.get("arc.agent.cost.total.usd")
            .tag("model", "gpt-4o")
            .tag("tenantId", "tenant-a")
            .counter()
        assertEquals(0.035, totalCounter.count(), 0.001, "누적 비용 카운터가 정확해야 한다")

        val flashTotal = registry.get("arc.agent.cost.total.usd")
            .tag("model", "gemini-2.5-flash")
            .tag("tenantId", "tenant-b")
            .counter()
        assertEquals(0.005, flashTotal.count(), 0.001, "다른 테넌트의 비용이 분리되어야 한다")
    }

    @Test
    fun `request cost에서 tenantId 미제공 시 unknown으로 태깅한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAgentMetrics(registry)

        metrics.recordRequestCost(0.01, "gpt-4o")

        val costSummary = registry.get("arc.agent.request.cost")
            .tag("model", "gpt-4o")
            .tag("tenantId", "unknown")
            .summary()
        assertEquals(1L, costSummary.count(), "메타데이터 없이도 기록되어야 한다")
    }

    @Test
    fun `active requests gauge를 추적한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAgentMetrics(registry)

        metrics.recordActiveRequests(5)
        val gauge = registry.get("arc.agent.active_requests").gauge()
        assertEquals(5.0, gauge.value(), 0.0, "Active requests gauge should reflect the set count")

        metrics.recordActiveRequests(3)
        assertEquals(3.0, gauge.value(), 0.0, "Active requests gauge should update to the new count")

        metrics.recordActiveRequests(0)
        assertEquals(0.0, gauge.value(), 0.0, "Active requests gauge should go back to zero")
    }
}
