package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.metrics.RecentTrustEvent
import com.arc.reactor.agent.metrics.RecentTrustEventReader
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.mcp.DefaultMcpManager
import com.arc.reactor.mcp.InMemoryMcpServerStore
import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.InMemoryScheduledJobExecutionStore
import com.arc.reactor.scheduler.JobExecutionStatus
import com.arc.reactor.scheduler.ScheduledJob
import com.arc.reactor.scheduler.ScheduledJobExecution
import com.arc.reactor.scheduler.ScheduledJobExecutionStore
import com.arc.reactor.scheduler.ScheduledJobType
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * OpsDashboardController에 대한 테스트.
 *
 * 운영 대시보드 REST API의 동작을 검증합니다.
 */
class OpsDashboardControllerTest {

    private fun exchange(role: UserRole): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to role
        )
        return exchange
    }

    private fun meterRegistryProvider(registry: MeterRegistry?): ObjectProvider<MeterRegistry> {
        val factory = StaticListableBeanFactory()
        if (registry != null) {
            factory.addBean("meterRegistry", registry)
        }
        return factory.getBeanProvider(MeterRegistry::class.java)
    }

    private fun schedulerProvider(scheduler: DynamicSchedulerService?): ObjectProvider<DynamicSchedulerService> {
        val factory = StaticListableBeanFactory()
        if (scheduler != null) {
            factory.addBean("schedulerService", scheduler)
        }
        return factory.getBeanProvider(DynamicSchedulerService::class.java)
    }

    private fun approvalProvider(store: PendingApprovalStore?): ObjectProvider<PendingApprovalStore> {
        val factory = StaticListableBeanFactory()
        if (store != null) {
            factory.addBean("pendingApprovalStore", store)
        }
        return factory.getBeanProvider(PendingApprovalStore::class.java)
    }

    private fun executionStoreProvider(store: ScheduledJobExecutionStore?): ObjectProvider<ScheduledJobExecutionStore> {
        val factory = StaticListableBeanFactory()
        if (store != null) {
            factory.addBean("scheduledJobExecutionStore", store)
        }
        return factory.getBeanProvider(ScheduledJobExecutionStore::class.java)
    }

    private fun agentMetricsProvider(agentMetrics: AgentMetrics?): ObjectProvider<AgentMetrics> {
        val factory = StaticListableBeanFactory()
        if (agentMetrics != null) {
            factory.addBean("agentMetrics", agentMetrics)
        }
        return factory.getBeanProvider(AgentMetrics::class.java)
    }

    @Test
    fun `대시보드 returns 403 for non-admin`() {
        val controller = OpsDashboardController(
            mcpManager = DefaultMcpManager(store = InMemoryMcpServerStore()),
            properties = AgentProperties(),
            meterRegistryProvider = meterRegistryProvider(null),
            schedulerServiceProvider = schedulerProvider(null),
            pendingApprovalStoreProvider = approvalProvider(null),
            executionStoreProvider = executionStoreProvider(null),
            agentMetricsProvider = agentMetricsProvider(null)
        )

        val response = controller.dashboard(names = null, exchange = exchange(UserRole.USER))
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "비관리자 요청은 403이어야 한다" }
    }

    @Test
    fun `대시보드 returns metric snapshot for requested names`() {
        val registry = SimpleMeterRegistry()
        registry.counter("arc.slack.inbound.total", "entrypoint", "events").increment(3.0)

        val controller = OpsDashboardController(
            mcpManager = DefaultMcpManager(store = InMemoryMcpServerStore()),
            properties = AgentProperties(rag = RagProperties(enabled = true)),
            meterRegistryProvider = meterRegistryProvider(registry),
            schedulerServiceProvider = schedulerProvider(null),
            pendingApprovalStoreProvider = approvalProvider(null),
            executionStoreProvider = executionStoreProvider(null),
            agentMetricsProvider = agentMetricsProvider(null)
        )

        val response = controller.dashboard(
            names = listOf("arc.slack.inbound.total"),
            exchange = exchange(UserRole.ADMIN)
        )
        assertEquals(HttpStatus.OK, response.statusCode) { "관리자 대시보드 요청은 200이어야 한다" }

        val body = response.body as OpsDashboardResponse
        assertTrue(body.ragEnabled) { "대시보드 응답에서 RAG가 활성화된 것으로 보고되어야 한다" }
        assertEquals(1, body.metrics.size) { "요청한 메트릭 1개가 반환되어야 한다" }
        assertEquals("arc.slack.inbound.total", body.metrics.first().name) { "메트릭 이름이 일치해야 한다" }
        assertEquals(3.0, body.metrics.first().measurements["count"]) {
            "카운터 증가값이 반영되어야 한다"
        }
        assertEquals(0, body.scheduler.totalJobs) { "스케줄러가 없으므로 totalJobs는 0이어야 한다" }
        assertEquals(0, body.recentSchedulerExecutions.size) { "최근 실행 이력이 없어야 한다" }
        assertEquals(0, body.approvals.pendingCount) { "대기 중인 승인이 없어야 한다" }
        assertEquals(0L, body.employeeValue.observedResponses) { "관측된 응답이 없어야 한다" }
        assertEquals(0, body.recentTrustEvents.size) { "최근 신뢰 이벤트가 없어야 한다" }
    }

    @Test
    fun `대시보드 exposes tagged series for stage duration metrics`() {
        val registry = SimpleMeterRegistry()
        Timer.builder("arc.agent.stage.duration")
            .tag("stage", "guard")
            .tag("channel", "web")
            .register(registry)
            .record(5, TimeUnit.MILLISECONDS)
        Timer.builder("arc.agent.stage.duration")
            .tag("stage", "agent_loop")
            .tag("channel", "web")
            .register(registry)
            .record(17, TimeUnit.MILLISECONDS)

        val controller = OpsDashboardController(
            mcpManager = DefaultMcpManager(store = InMemoryMcpServerStore()),
            properties = AgentProperties(rag = RagProperties(enabled = true)),
            meterRegistryProvider = meterRegistryProvider(registry),
            schedulerServiceProvider = schedulerProvider(null),
            pendingApprovalStoreProvider = approvalProvider(null),
            executionStoreProvider = executionStoreProvider(null),
            agentMetricsProvider = agentMetricsProvider(null)
        )

        val response = controller.dashboard(
            names = listOf("arc.agent.stage.duration"),
            exchange = exchange(UserRole.ADMIN)
        )
        assertEquals(HttpStatus.OK, response.statusCode) { "관리자 대시보드 요청은 200이어야 한다" }

        val body = response.body as OpsDashboardResponse
        val metric = body.metrics.first()
        assertEquals("arc.agent.stage.duration", metric.name) { "메트릭 이름이 일치해야 한다" }
        assertEquals(2, metric.meterCount) { "2개의 타이머가 등록되어야 한다" }
        assertEquals(2.0, metric.measurements["count"]) { "집계 카운트가 2여야 한다" }
        assertEquals(2, metric.series.size) { "series가 태그별로 2개여야 한다" }

        val guardSeries = metric.series.first { it.tags["stage"] == "guard" }
        assertEquals("web", guardSeries.tags["channel"]) { "guard 시리즈의 channel 태그가 web이어야 한다" }
        assertEquals(1.0, guardSeries.measurements["count"]) { "guard 시리즈의 카운트가 1이어야 한다" }
        val guardTotalTime = guardSeries.measurements["total_time"] ?: 0.0
        assertTrue(guardTotalTime > 0.0) {
            "guard 스테이지 시리즈가 타이머 지속 시간을 보존해야 한다"
        }

        val loopSeries = metric.series.first { it.tags["stage"] == "agent_loop" }
        assertEquals(1.0, loopSeries.measurements["count"]) { "agent_loop 시리즈의 카운트가 1이어야 한다" }
        val loopTotalTime = loopSeries.measurements["total_time"] ?: 0.0
        assertTrue(loopTotalTime > guardTotalTime) {
            "agent_loop 스테이지 시리즈의 타이머 지속 시간이 guard보다 커야 한다"
        }
    }

    @Test
    fun `대시보드 allows ADMIN_MANAGER`() {
        val controller = OpsDashboardController(
            mcpManager = DefaultMcpManager(store = InMemoryMcpServerStore()),
            properties = AgentProperties(),
            meterRegistryProvider = meterRegistryProvider(null),
            schedulerServiceProvider = schedulerProvider(null),
            pendingApprovalStoreProvider = approvalProvider(null),
            executionStoreProvider = executionStoreProvider(null),
            agentMetricsProvider = agentMetricsProvider(null)
        )

        val response = controller.dashboard(names = null, exchange = exchange(UserRole.ADMIN_MANAGER))
        assertEquals(HttpStatus.OK, response.statusCode) {
            "ADMIN_MANAGER는 대시보드에 접근할 수 있어야 한다"
        }
    }

    @Test
    fun `대시보드 allows ADMIN_DEVELOPER`() {
        val controller = OpsDashboardController(
            mcpManager = DefaultMcpManager(store = InMemoryMcpServerStore()),
            properties = AgentProperties(),
            meterRegistryProvider = meterRegistryProvider(null),
            schedulerServiceProvider = schedulerProvider(null),
            pendingApprovalStoreProvider = approvalProvider(null),
            executionStoreProvider = executionStoreProvider(null),
            agentMetricsProvider = agentMetricsProvider(null)
        )

        val response = controller.dashboard(names = null, exchange = exchange(UserRole.ADMIN_DEVELOPER))
        assertEquals(HttpStatus.OK, response.statusCode) {
            "ADMIN_DEVELOPER는 대시보드에 접근할 수 있어야 한다"
        }
    }

    @Test
    fun `metric은(는) names returns discovered metrics`() {
        val registry = SimpleMeterRegistry()
        registry.counter("arc.slack.inbound.total").increment()
        registry.counter("jvm.gc.pause").increment()

        val controller = OpsDashboardController(
            mcpManager = DefaultMcpManager(store = InMemoryMcpServerStore()),
            properties = AgentProperties(),
            meterRegistryProvider = meterRegistryProvider(registry),
            schedulerServiceProvider = schedulerProvider(null),
            pendingApprovalStoreProvider = approvalProvider(null),
            executionStoreProvider = executionStoreProvider(null),
            agentMetricsProvider = agentMetricsProvider(null)
        )

        val response = controller.metricNames(exchange = exchange(UserRole.ADMIN))
        assertEquals(HttpStatus.OK, response.statusCode) { "메트릭 이름 목록 요청은 200이어야 한다" }
        val names = response.body as List<*>
        assertTrue(names.contains("arc.slack.inbound.total")) {
            "메트릭 이름 목록에 arc.slack.inbound.total이 포함되어야 한다"
        }
        assertTrue(names.contains("jvm.gc.pause")) {
            "메트릭 이름 목록에 jvm.gc.pause가 포함되어야 한다"
        }
    }

    @Test
    fun `대시보드 includes scheduler approvals and response trust summaries`() {
        val registry = SimpleMeterRegistry()
        registry.counter("arc.agent.responses.unverified", "channel", "web").increment(2.0)
        registry.counter("arc.agent.output.guard.actions", "stage", "pipeline", "action", "rejected").increment(1.0)
        registry.counter("arc.agent.output.guard.actions", "stage", "pipeline", "action", "modified").increment(3.0)
        registry.counter("arc.agent.boundary.violations", "violation", "output_too_short", "policy", "fail")
            .increment(4.0)
        val agentMetrics = object : AgentMetrics, RecentTrustEventReader {
            override fun recordExecution(result: AgentResult) {}
            override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {}
            override fun recordGuardRejection(stage: String, reason: String) {}
            override fun responseValueSummary(): com.arc.reactor.agent.metrics.ResponseValueSummary {
                return com.arc.reactor.agent.metrics.ResponseValueSummary(
                    observedResponses = 12,
                    groundedResponses = 10,
                    blockedResponses = 2,
                    interactiveResponses = 9,
                    scheduledResponses = 3,
                    answerModeCounts = mapOf("operational" to 7, "knowledge" to 5),
                    channelCounts = mapOf("slack" to 8, "web" to 4),
                    toolFamilyCounts = mapOf("jira" to 4, "confluence" to 3, "work" to 2),
                    laneSummaries = listOf(
                        com.arc.reactor.agent.metrics.ResponseLaneSummary(
                            answerMode = "operational",
                            observedResponses = 7,
                            groundedResponses = 5,
                            blockedResponses = 2
                        ),
                        com.arc.reactor.agent.metrics.ResponseLaneSummary(
                            answerMode = "knowledge",
                            observedResponses = 5,
                            groundedResponses = 5,
                            blockedResponses = 0
                        )
                    )
                )
            }

            override fun topMissingQueries(
                limit: Int
            ): List<com.arc.reactor.agent.metrics.MissingQueryInsight> = listOf(
                com.arc.reactor.agent.metrics.MissingQueryInsight(
                    queryCluster = "1d409f34a41c",
                    queryLabel = "Question cluster 1d409f34a41c",
                    count = 2,
                    lastOccurredAt = Instant.parse("2026-03-07T09:09:00Z"),
                    blockReason = "unverified_sources"
                )
            )

            override fun recentTrustEvents(limit: Int): List<RecentTrustEvent> = listOf(
                RecentTrustEvent(
                    type = "unverified_response",
                    severity = "WARN",
                    channel = "web",
                    queryCluster = "9e1b4d532b8d",
                    queryLabel = "Question cluster 9e1b4d532b8d"
                ),
                RecentTrustEvent(
                    type = "boundary_violation",
                    severity = "FAIL",
                    violation = "output_too_short",
                    queryCluster = "ff22c5a78210",
                    queryLabel = "Prompt cluster ff22c5a78210"
                ),
                RecentTrustEvent(
                    type = "output_guard",
                    severity = "FAIL",
                    action = "rejected",
                    reason = "blocked",
                    queryCluster = "6d71ce0c7512",
                    queryLabel = "Prompt cluster 6d71ce0c7512"
                )
            ).take(limit)
        }

        val scheduler = mockk<DynamicSchedulerService>()
        every { scheduler.list() } returns listOf(
            ScheduledJob(id = "j1", name = "Morning", cronExpression = "0 9 * * * *", enabled = true),
            ScheduledJob(
                id = "j2",
                name = "Release",
                cronExpression = "0 0 10 * * *",
                enabled = true,
                jobType = ScheduledJobType.AGENT,
                lastStatus = JobExecutionStatus.RUNNING
            ),
            ScheduledJob(
                id = "j3",
                name = "Digest",
                cronExpression = "0 0 11 * * *",
                enabled = true,
                lastStatus = JobExecutionStatus.FAILED
            )
        )
        val executionStore = InMemoryScheduledJobExecutionStore()
        executionStore.save(
            ScheduledJobExecution(
                id = "exec-1",
                jobId = "j2",
                jobName = "Release",
                status = JobExecutionStatus.RUNNING,
                result = "Running release digest",
                durationMs = 1500,
                startedAt = Instant.parse("2026-03-07T09:00:00Z")
            )
        )
        executionStore.save(
            ScheduledJobExecution(
                id = "exec-2",
                jobId = "j3",
                jobName = "Digest",
                status = JobExecutionStatus.FAILED,
                result = "Job 'Digest' failed: MCP server 'atlassian' is not connected",
                durationMs = 2500,
                dryRun = true,
                startedAt = Instant.parse("2026-03-07T09:05:00Z"),
                completedAt = Instant.parse("2026-03-07T09:05:03Z")
            )
        )

        val pendingStore = mockk<PendingApprovalStore>()
        every { pendingStore.listPending() } returns listOf(
            com.arc.reactor.approval.ApprovalSummary(
                id = "a1",
                runId = "run-1",
                userId = "u1",
                toolName = "jira_transition_issue",
                arguments = emptyMap(),
                requestedAt = java.time.Instant.parse("2026-03-07T09:00:00Z"),
                status = com.arc.reactor.approval.ApprovalStatus.PENDING
            )
        )
        every { pendingStore.listPendingByUser(any()) } returns emptyList()
        every { pendingStore.approve(any(), any()) } returns true
        every { pendingStore.reject(any(), any()) } returns true

        val controller = OpsDashboardController(
            mcpManager = DefaultMcpManager(store = InMemoryMcpServerStore()),
            properties = AgentProperties(),
            meterRegistryProvider = meterRegistryProvider(registry),
            schedulerServiceProvider = schedulerProvider(scheduler),
            pendingApprovalStoreProvider = approvalProvider(pendingStore),
            executionStoreProvider = executionStoreProvider(executionStore),
            agentMetricsProvider = agentMetricsProvider(agentMetrics)
        )

        val response = controller.dashboard(names = null, exchange = exchange(UserRole.ADMIN))
        assertEquals(HttpStatus.OK, response.statusCode) { "관리자 대시보드 요청은 200이어야 한다" }

        val body = response.body as OpsDashboardResponse
        assertEquals(3, body.scheduler.totalJobs) { "등록된 스케줄 잡이 3개여야 한다" }
        assertEquals(2, body.scheduler.attentionBacklog) {
            "주의 필요 잡이 RUNNING+FAILED 합쳐 2개여야 한다"
        }
        assertEquals(1, body.scheduler.agentJobs) { "에이전트 잡이 1개여야 한다" }
        assertEquals(2, body.recentSchedulerExecutions.size) { "최근 실행 이력이 2개여야 한다" }
        assertEquals("Release", body.recentSchedulerExecutions[1].jobName) {
            "두 번째 실행 이력의 잡 이름이 Release여야 한다"
        }
        assertEquals("AGENT", body.recentSchedulerExecutions[1].jobType) {
            "두 번째 실행 이력의 잡 타입이 AGENT여야 한다"
        }
        assertEquals(
            "MCP server 'atlassian' is not connected",
            body.recentSchedulerExecutions[0].failureReason
        ) { "첫 번째 실행 이력의 실패 원인이 일치해야 한다" }
        assertEquals(
            "Running release digest",
            body.recentSchedulerExecutions[1].resultPreview
        ) { "두 번째 실행 이력의 결과 미리보기가 일치해야 한다" }
        assertEquals(1, body.approvals.pendingCount) { "대기 중인 승인이 1개여야 한다" }
        assertEquals(2L, body.responseTrust.unverifiedResponses) { "미검증 응답이 2개여야 한다" }
        assertEquals(1L, body.responseTrust.outputGuardRejected) { "출력 가드 거부가 1개여야 한다" }
        assertEquals(3L, body.responseTrust.outputGuardModified) { "출력 가드 수정이 3개여야 한다" }
        assertEquals(4L, body.responseTrust.boundaryFailures) { "경계 위반이 4개여야 한다" }
        assertEquals(12L, body.employeeValue.observedResponses) { "관측된 응답이 12개여야 한다" }
        assertEquals(10L, body.employeeValue.groundedResponses) { "근거 있는 응답이 10개여야 한다" }
        assertEquals(83, body.employeeValue.groundedRatePercent) { "근거 비율이 83%여야 한다" }
        assertEquals(2L, body.employeeValue.blockedResponses) { "차단된 응답이 2개여야 한다" }
        assertEquals(7L, body.employeeValue.answerModes["operational"]) {
            "operational 답변 모드가 7개여야 한다"
        }
        assertEquals("slack", body.employeeValue.channels.first().key) { "첫 번째 채널이 slack이어야 한다" }
        assertEquals(
            71,
            body.employeeValue.lanes.first { it.answerMode == "operational" }.groundedRatePercent
        ) { "operational 레인의 근거 비율이 71%여야 한다" }
        assertEquals(
            0,
            body.employeeValue.lanes.first { it.answerMode == "knowledge" }.blockedResponses
        ) { "knowledge 레인의 차단 응답이 0이어야 한다" }
        assertEquals(
            "1d409f34a41c",
            body.employeeValue.topMissingQueries[0].queryCluster
        ) { "상위 누락 쿼리 클러스터가 일치해야 한다" }
        assertEquals(
            "Question cluster 1d409f34a41c",
            body.employeeValue.topMissingQueries[0].queryLabel
        ) { "상위 누락 쿼리 레이블이 일치해야 한다" }
        assertEquals(3, body.recentTrustEvents.size) { "최근 신뢰 이벤트가 3개여야 한다" }
        assertEquals("unverified_response", body.recentTrustEvents[0].type) {
            "첫 번째 신뢰 이벤트 타입이 unverified_response여야 한다"
        }
        assertEquals("9e1b4d532b8d", body.recentTrustEvents[0].queryCluster) {
            "첫 번째 신뢰 이벤트 쿼리 클러스터가 일치해야 한다"
        }
        assertEquals("Question cluster 9e1b4d532b8d", body.recentTrustEvents[0].queryLabel) {
            "첫 번째 신뢰 이벤트 쿼리 레이블이 일치해야 한다"
        }
    }

    @Test
    fun `dashboard falls back to in-memory trust metrics when meter registry은(는) unavailable이다`() {
        val agentMetrics = NoOpAgentMetrics().apply {
            recordUnverifiedResponse(
                mapOf(
                    "channel" to "web",
                    "queryCluster" to "a207b424cb25",
                    "queryLabel" to "Prompt cluster a207b424cb25",
                    "blockReason" to "unverified_sources"
                )
            )
            recordOutputGuardAction(
                stage = "pipeline",
                action = "modified",
                reason = "masked",
                metadata = mapOf("queryCluster" to "2b7f4259f520", "queryLabel" to "Prompt cluster 2b7f4259f520")
            )
            recordBoundaryViolation(
                violation = "output_too_short",
                policy = "fail",
                limit = 120,
                actual = 12,
                metadata = mapOf("queryCluster" to "3ce6990f6d8d", "queryLabel" to "Prompt cluster 3ce6990f6d8d")
            )
        }

        val controller = OpsDashboardController(
            mcpManager = DefaultMcpManager(store = InMemoryMcpServerStore()),
            properties = AgentProperties(),
            meterRegistryProvider = meterRegistryProvider(null),
            schedulerServiceProvider = schedulerProvider(null),
            pendingApprovalStoreProvider = approvalProvider(null),
            executionStoreProvider = executionStoreProvider(null),
            agentMetricsProvider = agentMetricsProvider(agentMetrics)
        )

        val response = controller.dashboard(names = null, exchange = exchange(UserRole.ADMIN))
        assertEquals(HttpStatus.OK, response.statusCode) { "관리자 대시보드 요청은 200이어야 한다" }

        val body = response.body as OpsDashboardResponse
        assertEquals(1L, body.responseTrust.unverifiedResponses) {
            "인메모리 기반 미검증 응답이 1개여야 한다"
        }
        assertEquals(0L, body.responseTrust.outputGuardRejected) { "출력 가드 거부가 0이어야 한다" }
        assertEquals(1L, body.responseTrust.outputGuardModified) { "출력 가드 수정이 1개여야 한다" }
        assertEquals(1L, body.responseTrust.boundaryFailures) { "경계 위반이 1개여야 한다" }
        assertEquals(0L, body.employeeValue.observedResponses) { "관측된 응답이 없어야 한다" }
        assertEquals(0, body.employeeValue.lanes.size) { "레인 정보가 없어야 한다" }
        assertEquals(3, body.recentTrustEvents.size) { "최근 신뢰 이벤트가 3개여야 한다" }
        assertEquals("unverified_response", body.recentTrustEvents[2].type) {
            "세 번째 신뢰 이벤트 타입이 unverified_response여야 한다"
        }
        assertEquals("a207b424cb25", body.recentTrustEvents[2].queryCluster) {
            "세 번째 신뢰 이벤트 쿼리 클러스터가 일치해야 한다"
        }
    }
}
