package com.arc.reactor.admin.query

import com.arc.reactor.admin.model.CostDashboard
import com.arc.reactor.admin.model.OverviewDashboard
import com.arc.reactor.admin.model.QualityDashboard
import com.arc.reactor.admin.model.ToolDashboard
import com.arc.reactor.admin.model.UsageDashboard
import com.arc.reactor.admin.tenant.TenantStore
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 테넌트별 대시보드 데이터를 조합하는 서비스.
 *
 * 개요, 사용량, 품질, 도구, 비용 대시보드를 제공한다.
 * [MetricQueryService], [SloService], [TenantStore]에서 데이터를 조합한다.
 *
 * @see TenantAdminController 이 서비스를 사용하는 컨트롤러
 */
class DashboardService(
    private val jdbcTemplate: JdbcTemplate,
    private val queryService: MetricQueryService,
    private val sloService: SloService,
    private val tenantStore: TenantStore
) {

    /** 테넌트 개요 대시보드를 생성한다. 테넌트가 없으면 null을 반환한다. */
    fun getOverview(tenantId: String): OverviewDashboard? {
        val now = Instant.now()
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)
        val tenant = tenantStore.findById(tenantId) ?: return null
        val usage = queryService.getCurrentMonthUsage(tenantId)
        val sloStatus = sloService.getSloStatus(tenantId, tenant.sloAvailability, tenant.sloLatencyP99Ms)
        return OverviewDashboard(
            totalRequests = usage.requests,
            successRate = queryService.getSuccessRate(tenantId, thirtyDaysAgo, now),
            avgResponseTimeMs = queryAvgResponseTime(tenantId, thirtyDaysAgo, now),
            apdexScore = sloService.getApdex(tenantId, thirtyDaysAgo, now).score,
            sloAvailability = sloStatus.availability.current,
            errorBudgetRemaining = sloStatus.errorBudget.budgetRemaining,
            monthlyCost = usage.costUsd
        )
    }

    /** 테넌트 사용량 대시보드를 생성한다. */
    fun getUsage(tenantId: String, from: Instant, to: Instant): UsageDashboard {
        return UsageDashboard(
            timeSeries = queryService.getRequestTimeSeries(tenantId, from, to),
            channelDistribution = queryChannelDistribution(tenantId, from, to),
            topUsers = queryService.getTopUsers(tenantId, from, to)
        )
    }

    /** 테넌트 품질 대시보드를 생성한다. */
    fun getQuality(tenantId: String, from: Instant, to: Instant): QualityDashboard {
        val percentiles = queryService.getLatencyPercentiles(tenantId, from, to)
        return QualityDashboard(
            latencyP50 = percentiles["p50"] ?: 0,
            latencyP95 = percentiles["p95"] ?: 0,
            latencyP99 = percentiles["p99"] ?: 0,
            errorDistribution = queryService.getErrorDistribution(tenantId, from, to)
        )
    }

    /** 테넌트 도구 대시보드를 생성한다. */
    fun getTools(tenantId: String, from: Instant, to: Instant): ToolDashboard {
        val ranking = queryService.getToolRanking(tenantId, from, to)
        return ToolDashboard(
            toolRanking = ranking,
            slowestTools = ranking.sortedByDescending { it.p95DurationMs }.take(5)
        )
    }

    /** 테넌트 비용 대시보드를 생성한다. */
    fun getCost(tenantId: String, from: Instant, to: Instant): CostDashboard {
        return CostDashboard(
            monthlyCost = queryService.getCurrentMonthUsage(tenantId).costUsd,
            costByModel = queryCostByModel(tenantId, from, to)
        )
    }

    /** 평균 응답 시간(ms)을 조회한다. */
    private fun queryAvgResponseTime(tenantId: String, from: Instant, to: Instant): Long {
        return jdbcTemplate.queryForObject(
            """SELECT COALESCE(AVG(duration_ms), 0)::BIGINT
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ?""",
            Long::class.java,
            tenantId, Timestamp.from(from), Timestamp.from(to)
        ) ?: 0
    }

    /** 채널별 요청 분포를 조회한다. */
    private fun queryChannelDistribution(tenantId: String, from: Instant, to: Instant): Map<String, Long> {
        return jdbcTemplate.query(
            """SELECT COALESCE(channel, 'unknown') AS channel, COUNT(*) AS count
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ?
               GROUP BY channel ORDER BY count DESC""",
            { rs, _ -> rs.getString("channel") to rs.getLong("count") },
            tenantId, Timestamp.from(from), Timestamp.from(to)
        ).toMap()
    }

    /** 모델별 비용 합계를 조회한다. */
    private fun queryCostByModel(tenantId: String, from: Instant, to: Instant): Map<String, java.math.BigDecimal> {
        return jdbcTemplate.query(
            """SELECT model, SUM(estimated_cost_usd) AS cost
               FROM metric_token_usage
               WHERE tenant_id = ? AND time >= ? AND time < ?
               GROUP BY model ORDER BY cost DESC""",
            { rs, _ -> rs.getString("model") to rs.getBigDecimal("cost") },
            tenantId, Timestamp.from(from), Timestamp.from(to)
        ).toMap()
    }
}
