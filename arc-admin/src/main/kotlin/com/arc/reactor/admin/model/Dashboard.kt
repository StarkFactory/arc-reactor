package com.arc.reactor.admin.model

import java.math.BigDecimal
import java.time.Instant

/** 테넌트 개요 대시보드. 요청 수, 성공률, APDEX, SLO, 비용을 포함한다. */
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

/** 테넌트 사용량 대시보드. 시계열, 채널 분포, 상위 사용자를 포함한다. */
data class UsageDashboard(
    val timeSeries: List<TimeSeriesPoint> = emptyList(),
    val channelDistribution: Map<String, Long> = emptyMap(),
    val topUsers: List<UserUsageSummary> = emptyList(),
    val avgTurnsPerSession: Double = 0.0,
    val sessionAbandonRate: Double = 0.0,
    val sessionResolveRate: Double = 0.0
)

/** 시계열 데이터 포인트. */
data class TimeSeriesPoint(
    val time: Instant,
    val value: Double
)

/** 사용자별 사용량 요약. */
data class UserUsageSummary(
    val userLabel: String,
    val requests: Long = 0,
    val tokens: Long = 0,
    val lastActivity: Instant? = null
)

/** 테넌트 품질 대시보드. 성공률 추이, APDEX 추이, 지연 백분위, 오류 분포를 포함한다. */
data class QualityDashboard(
    val successRateTrend: List<TimeSeriesPoint> = emptyList(),
    val apdexTrend: List<TimeSeriesPoint> = emptyList(),
    val latencyP50: Long = 0,
    val latencyP95: Long = 0,
    val latencyP99: Long = 0,
    val errorDistribution: Map<String, Long> = emptyMap()
)

/** 테넌트 도구 대시보드. 도구 랭킹 및 가장 느린 도구를 포함한다. */
data class ToolDashboard(
    val toolRanking: List<ToolUsageSummary> = emptyList(),
    val slowestTools: List<ToolUsageSummary> = emptyList()
)

/** 도구별 사용량 요약. */
data class ToolUsageSummary(
    val toolName: String,
    val calls: Long = 0,
    val successRate: Double = 0.0,
    val avgDurationMs: Long = 0,
    val p95DurationMs: Long = 0,
    val mcpServerName: String? = null
)

/** 테넌트 비용 대시보드. 월 비용, 일별 추이, 모델별 비용을 포함한다. */
data class CostDashboard(
    val monthlyCost: BigDecimal = BigDecimal.ZERO,
    val dailyCostTrend: List<TimeSeriesPoint> = emptyList(),
    val costByModel: Map<String, BigDecimal> = emptyMap(),
    val costPerResolution: BigDecimal = BigDecimal.ZERO,
    val cachedTokenRatio: Double = 0.0,
    val budgetUsagePercent: Double = 0.0
)

/** 플랫폼 헬스 대시보드. 파이프라인 상태, 캐시 히트, 활성 알림을 포함한다. */
data class PlatformHealthDashboard(
    val services: List<ServiceStatus> = emptyList(),
    val pipelineBufferUsage: Double = 0.0,
    val pipelineDropRate: Double = 0.0,
    val pipelineWriteLatencyMs: Long = 0,
    val activeAlerts: Int = 0,
    val cacheExactHits: Long = 0,
    val cacheSemanticHits: Long = 0,
    val cacheMisses: Long = 0
)

/** 서비스 상태 정보. */
data class ServiceStatus(
    val name: String,
    val status: String = "UP",
    val responseTimeMs: Long = 0,
    val lastChecked: Instant? = null
)

/** 테넌트 분석 요약. 플랫폼 관리자가 전체 테넌트를 조망할 때 사용. */
data class TenantAnalyticsSummary(
    val tenantId: String,
    val tenantName: String,
    val plan: String,
    val requests: Long = 0,
    val cost: BigDecimal = BigDecimal.ZERO,
    val sloStatus: String = "OK",
    val quotaUsagePercent: Double = 0.0
)
