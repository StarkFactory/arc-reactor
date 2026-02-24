package com.arc.reactor.admin.model

import java.math.BigDecimal
import java.time.Instant

data class OverviewDashboard(
    val totalRequests: Long = 0,
    val successRate: Double = 0.0,
    val avgResponseTimeMs: Long = 0,
    val apdexScore: Double = 0.0,
    val sloAvailability: Double = 0.0,
    val errorBudgetRemaining: Double = 0.0,
    val monthlyCost: BigDecimal = BigDecimal.ZERO,
    val activeAlerts: Int = 0
)

data class UsageDashboard(
    val timeSeries: List<TimeSeriesPoint> = emptyList(),
    val channelDistribution: Map<String, Long> = emptyMap(),
    val topUsers: List<UserUsageSummary> = emptyList(),
    val avgTurnsPerSession: Double = 0.0,
    val sessionAbandonRate: Double = 0.0,
    val sessionResolveRate: Double = 0.0
)

data class TimeSeriesPoint(
    val time: Instant,
    val value: Double
)

data class UserUsageSummary(
    val userId: String,
    val requests: Long = 0,
    val tokens: Long = 0,
    val lastActivity: Instant? = null
)

data class QualityDashboard(
    val successRateTrend: List<TimeSeriesPoint> = emptyList(),
    val apdexTrend: List<TimeSeriesPoint> = emptyList(),
    val latencyP50: Long = 0,
    val latencyP95: Long = 0,
    val latencyP99: Long = 0,
    val errorDistribution: Map<String, Long> = emptyMap()
)

data class ToolDashboard(
    val toolRanking: List<ToolUsageSummary> = emptyList(),
    val slowestTools: List<ToolUsageSummary> = emptyList()
)

data class ToolUsageSummary(
    val toolName: String,
    val calls: Long = 0,
    val successRate: Double = 0.0,
    val avgDurationMs: Long = 0,
    val p95DurationMs: Long = 0,
    val mcpServerName: String? = null
)

data class CostDashboard(
    val monthlyCost: BigDecimal = BigDecimal.ZERO,
    val dailyCostTrend: List<TimeSeriesPoint> = emptyList(),
    val costByModel: Map<String, BigDecimal> = emptyMap(),
    val costPerResolution: BigDecimal = BigDecimal.ZERO,
    val cachedTokenRatio: Double = 0.0,
    val budgetUsagePercent: Double = 0.0
)

data class PlatformHealthDashboard(
    val services: List<ServiceStatus> = emptyList(),
    val pipelineBufferUsage: Double = 0.0,
    val pipelineDropRate: Double = 0.0,
    val pipelineWriteLatencyMs: Long = 0,
    val activeAlerts: Int = 0
)

data class ServiceStatus(
    val name: String,
    val status: String = "UP",
    val responseTimeMs: Long = 0,
    val lastChecked: Instant? = null
)

data class TenantAnalyticsSummary(
    val tenantId: String,
    val tenantName: String,
    val plan: String,
    val requests: Long = 0,
    val cost: BigDecimal = BigDecimal.ZERO,
    val sloStatus: String = "OK",
    val quotaUsagePercent: Double = 0.0
)
