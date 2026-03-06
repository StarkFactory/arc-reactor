package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.metrics.AgentMetrics
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
    fun `dashboard returns 403 for non-admin`() {
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
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `dashboard returns metric snapshot for requested names`() {
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
        assertEquals(HttpStatus.OK, response.statusCode)

        val body = response.body as OpsDashboardResponse
        assertTrue(body.ragEnabled, "RAG should be reported as enabled in dashboard body")
        assertEquals(1, body.metrics.size)
        assertEquals("arc.slack.inbound.total", body.metrics.first().name)
        assertEquals(3.0, body.metrics.first().measurements["count"])
        assertEquals(0, body.scheduler.totalJobs)
        assertEquals(0, body.recentSchedulerExecutions.size)
        assertEquals(0, body.approvals.pendingCount)
        assertEquals(0, body.recentTrustEvents.size)
    }

    @Test
    fun `dashboard allows ADMIN_MANAGER`() {
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
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `dashboard allows ADMIN_DEVELOPER`() {
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
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `metric names returns discovered metrics`() {
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
        assertEquals(HttpStatus.OK, response.statusCode)
        val names = response.body as List<*>
        assertTrue(names.contains("arc.slack.inbound.total"), "Metric names should include arc.slack.inbound.total")
        assertTrue(names.contains("jvm.gc.pause"), "Metric names should include jvm.gc.pause")
    }

    @Test
    fun `dashboard includes scheduler approvals and response trust summaries`() {
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
            override fun recentTrustEvents(limit: Int): List<RecentTrustEvent> = listOf(
                RecentTrustEvent(
                    type = "unverified_response",
                    severity = "WARN",
                    channel = "web",
                    runId = "run-1",
                    userId = "u1",
                    queryPreview = "Show the current policy"
                ),
                RecentTrustEvent(
                    type = "boundary_violation",
                    severity = "FAIL",
                    violation = "output_too_short",
                    runId = "run-2",
                    userId = "u2"
                ),
                RecentTrustEvent(
                    type = "output_guard",
                    severity = "FAIL",
                    action = "rejected",
                    reason = "blocked",
                    runId = "run-3"
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
        assertEquals(HttpStatus.OK, response.statusCode)

        val body = response.body as OpsDashboardResponse
        assertEquals(3, body.scheduler.totalJobs)
        assertEquals(2, body.scheduler.attentionBacklog)
        assertEquals(1, body.scheduler.agentJobs)
        assertEquals(2, body.recentSchedulerExecutions.size)
        assertEquals("Release", body.recentSchedulerExecutions[1].jobName)
        assertEquals("AGENT", body.recentSchedulerExecutions[1].jobType)
        assertEquals("MCP server 'atlassian' is not connected", body.recentSchedulerExecutions[0].failureReason)
        assertEquals("Running release digest", body.recentSchedulerExecutions[1].resultPreview)
        assertEquals(1, body.approvals.pendingCount)
        assertEquals(2L, body.responseTrust.unverifiedResponses)
        assertEquals(1L, body.responseTrust.outputGuardRejected)
        assertEquals(3L, body.responseTrust.outputGuardModified)
        assertEquals(4L, body.responseTrust.boundaryFailures)
        assertEquals(3, body.recentTrustEvents.size)
        assertEquals("unverified_response", body.recentTrustEvents[0].type)
        assertEquals("run-1", body.recentTrustEvents[0].runId)
        assertEquals("Show the current policy", body.recentTrustEvents[0].queryPreview)
    }
}
