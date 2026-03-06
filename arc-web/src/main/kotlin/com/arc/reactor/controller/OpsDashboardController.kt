package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.RecentTrustEventReader
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.JobExecutionStatus
import com.arc.reactor.scheduler.ScheduledJobExecutionStore
import com.arc.reactor.scheduler.ScheduledJobType
import io.micrometer.core.instrument.MeterRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

@Tag(name = "Ops Dashboard", description = "Operational dashboard APIs (ADMIN/ADMIN_MANAGER/ADMIN_DEVELOPER)")
@RestController
@RequestMapping("/api/ops")
class OpsDashboardController(
    private val mcpManager: McpManager,
    private val properties: AgentProperties,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
    private val schedulerServiceProvider: ObjectProvider<DynamicSchedulerService>,
    private val pendingApprovalStoreProvider: ObjectProvider<PendingApprovalStore>,
    private val executionStoreProvider: ObjectProvider<ScheduledJobExecutionStore>,
    private val agentMetricsProvider: ObjectProvider<AgentMetrics>
) {

    @Operation(summary = "Get operations dashboard snapshot (admin)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Operations dashboard snapshot"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/dashboard")
    fun dashboard(
        @RequestParam(required = false) names: List<String>?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()

        val metricNames = names?.filter { it.isNotBlank() }?.toSet()?.takeIf { it.isNotEmpty() } ?: DEFAULT_METRIC_NAMES
        val registry = meterRegistryProvider.ifAvailable

        val response = OpsDashboardResponse(
            generatedAt = Instant.now().toEpochMilli(),
            ragEnabled = properties.rag.enabled,
            mcp = mcpSummary(),
            scheduler = schedulerSummary(),
            recentSchedulerExecutions = recentSchedulerExecutions(),
            approvals = approvalSummary(),
            responseTrust = responseTrustSummary(registry),
            recentTrustEvents = recentTrustEvents(),
            metrics = metricNames.map { name -> metricSnapshot(name, registry) }
        )
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "List available ops metric names (admin)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of available metric names"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/metrics/names")
    fun metricNames(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val registry = meterRegistryProvider.ifAvailable
        val names = registry?.meters
            ?.asSequence()
            ?.map { it.id.name }
            ?.filter {
                it.startsWith("arc.") ||
                    it.startsWith("jvm.") ||
                    it.startsWith("process.") ||
                    it.startsWith("system.")
            }
            ?.distinct()
            ?.sorted()
            ?.toList()
            ?: emptyList()
        return ResponseEntity.ok(names)
    }

    private fun mcpSummary(): McpStatusSummary {
        val servers = mcpManager.listServers()
        val statusCounts = servers
            .groupingBy { mcpManager.getStatus(it.name) ?: McpServerStatus.PENDING }
            .eachCount()
            .mapKeys { it.key.name }

        return McpStatusSummary(
            total = servers.size,
            statusCounts = statusCounts
        )
    }

    private fun metricSnapshot(name: String, registry: MeterRegistry?): OpsMetricSnapshot {
        if (registry == null) {
            return OpsMetricSnapshot(
                name = name,
                meterCount = 0,
                measurements = emptyMap()
            )
        }

        val meters = registry.find(name).meters()
        if (meters.isEmpty()) {
            return OpsMetricSnapshot(
                name = name,
                meterCount = 0,
                measurements = emptyMap()
            )
        }

        val aggregated = linkedMapOf<String, Double>()
        meters.forEach { meter ->
            meter.measure().forEach { measurement ->
                val key = measurement.statistic.toMetricKey()
                aggregated[key] = (aggregated[key] ?: 0.0) + measurement.value
            }
        }

        return OpsMetricSnapshot(
            name = name,
            meterCount = meters.size,
            measurements = aggregated
        )
    }

    private fun schedulerSummary(): SchedulerOpsSummary {
        val jobs = schedulerServiceProvider.ifAvailable?.list().orEmpty()
        val enabledJobs = jobs.count { it.enabled }
        val runningJobs = jobs.count { it.lastStatus == JobExecutionStatus.RUNNING }
        val failedJobs = jobs.count { it.enabled && it.lastStatus == JobExecutionStatus.FAILED }
        val agentJobs = jobs.count { it.enabled && it.jobType == ScheduledJobType.AGENT }

        return SchedulerOpsSummary(
            totalJobs = jobs.size,
            enabledJobs = enabledJobs,
            runningJobs = runningJobs,
            failedJobs = failedJobs,
            attentionBacklog = runningJobs + failedJobs,
            agentJobs = agentJobs
        )
    }

    private fun approvalSummary(): ApprovalOpsSummary {
        val pendingCount = pendingApprovalStoreProvider.ifAvailable?.listPending()?.size ?: 0
        return ApprovalOpsSummary(pendingCount = pendingCount)
    }

    private fun recentSchedulerExecutions(limit: Int = 6): List<RecentSchedulerExecutionSummary> {
        val jobTypes = schedulerServiceProvider.ifAvailable?.list()
            ?.associate { it.id to it.jobType.name }
            .orEmpty()
        return executionStoreProvider.ifAvailable?.findRecent(limit)
            ?.map { execution ->
                RecentSchedulerExecutionSummary(
                    id = execution.id,
                    jobId = execution.jobId,
                    jobName = execution.jobName,
                    jobType = jobTypes[execution.jobId],
                    status = execution.status.name,
                    resultPreview = schedulerResultPreview(execution.result),
                    failureReason = schedulerFailureReason(execution.result),
                    dryRun = execution.dryRun,
                    durationMs = execution.durationMs,
                    startedAt = execution.startedAt.toEpochMilli(),
                    completedAt = execution.completedAt?.toEpochMilli()
                )
            }
            .orEmpty()
    }

    private fun responseTrustSummary(registry: MeterRegistry?): ResponseTrustSummary {
        val trustReader = agentMetricsProvider.ifAvailable as? RecentTrustEventReader
        val unverifiedResponses = metricCounterValue(registry, "arc.agent.responses.unverified")
            .coerceAtLeast(trustReader?.unverifiedResponsesCount() ?: 0)
        val outputGuardRejected = metricCounterValue(
            registry,
            "arc.agent.output.guard.actions",
            mapOf("action" to "rejected")
        ).coerceAtLeast(trustReader?.outputGuardRejectedCount() ?: 0)
        val outputGuardModified = metricCounterValue(
            registry,
            "arc.agent.output.guard.actions",
            mapOf("action" to "modified")
        ).coerceAtLeast(trustReader?.outputGuardModifiedCount() ?: 0)
        val boundaryFailures = metricCounterValue(
            registry,
            "arc.agent.boundary.violations",
            mapOf("policy" to "fail")
        ).coerceAtLeast(trustReader?.boundaryFailuresCount() ?: 0)
        return ResponseTrustSummary(
            unverifiedResponses = unverifiedResponses,
            outputGuardRejected = outputGuardRejected,
            outputGuardModified = outputGuardModified,
            boundaryFailures = boundaryFailures
        )
    }

    private fun metricCounterValue(
        registry: MeterRegistry?,
        name: String,
        requiredTags: Map<String, String> = emptyMap()
    ): Long {
        if (registry == null) return 0
        return registry.find(name).meters()
            .asSequence()
            .filter { meter ->
                requiredTags.all { (key, value) -> meter.id.getTag(key) == value }
            }
            .sumOf { meter ->
                meter.measure()
                    .firstOrNull { it.statistic.toMetricKey() == "count" }
                    ?.value
                    ?.toLong()
                    ?: 0L
            }
    }

    private fun recentTrustEvents(limit: Int = 8): List<RecentTrustEventSummary> {
        val reader = agentMetricsProvider.ifAvailable as? RecentTrustEventReader ?: return emptyList()
        return reader.recentTrustEvents(limit).map { event ->
            RecentTrustEventSummary(
                occurredAt = event.occurredAt.toEpochMilli(),
                type = event.type,
                severity = event.severity,
                action = event.action,
                stage = event.stage,
                reason = event.reason,
                violation = event.violation,
                policy = event.policy,
                channel = event.channel,
                runId = event.runId,
                userId = event.userId,
                queryPreview = event.queryPreview
            )
        }
    }

    private fun io.micrometer.core.instrument.Statistic.toMetricKey(): String = name.lowercase()

    companion object {
        private val DEFAULT_METRIC_NAMES = setOf(
            "arc.agent.executions",
            "arc.agent.errors",
            "arc.agent.tool.calls",
            "arc.agent.output.guard.actions",
            "arc.agent.boundary.violations",
            "arc.agent.responses.unverified",
            "arc.slack.inbound.total",
            "arc.slack.duplicate.total",
            "arc.slack.dropped.total",
            "arc.slack.handler.duration",
            "arc.slack.api.duration",
            "arc.slack.api.retry.total"
        )
    }
}

data class OpsDashboardResponse(
    val generatedAt: Long,
    val ragEnabled: Boolean,
    val mcp: McpStatusSummary,
    val scheduler: SchedulerOpsSummary,
    val recentSchedulerExecutions: List<RecentSchedulerExecutionSummary>,
    val approvals: ApprovalOpsSummary,
    val responseTrust: ResponseTrustSummary,
    val recentTrustEvents: List<RecentTrustEventSummary>,
    val metrics: List<OpsMetricSnapshot>
)

data class McpStatusSummary(
    val total: Int,
    val statusCounts: Map<String, Int>
)

data class OpsMetricSnapshot(
    val name: String,
    val meterCount: Int,
    val measurements: Map<String, Double>
)

data class SchedulerOpsSummary(
    val totalJobs: Int,
    val enabledJobs: Int,
    val runningJobs: Int,
    val failedJobs: Int,
    val attentionBacklog: Int,
    val agentJobs: Int
)

data class RecentSchedulerExecutionSummary(
    val id: String,
    val jobId: String,
    val jobName: String,
    val jobType: String?,
    val status: String,
    val resultPreview: String?,
    val failureReason: String?,
    val dryRun: Boolean,
    val durationMs: Long,
    val startedAt: Long,
    val completedAt: Long?
)

data class ApprovalOpsSummary(
    val pendingCount: Int
)

data class ResponseTrustSummary(
    val unverifiedResponses: Long,
    val outputGuardRejected: Long,
    val outputGuardModified: Long,
    val boundaryFailures: Long
)

data class RecentTrustEventSummary(
    val occurredAt: Long,
    val type: String,
    val severity: String,
    val action: String?,
    val stage: String?,
    val reason: String?,
    val violation: String?,
    val policy: String?,
    val channel: String?,
    val runId: String?,
    val userId: String?,
    val queryPreview: String?
)
