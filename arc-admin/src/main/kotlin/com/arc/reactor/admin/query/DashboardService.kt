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
        val successRate = queryService.getSuccessRate(tenantId, thirtyDaysAgo, now)
        val apdex = sloService.getApdex(tenantId, thirtyDaysAgo, now)
        val sloStatus = sloService.getSloStatus(
            tenantId,
            tenant.sloAvailability,
            tenant.sloLatencyP99Ms
        )

        val usage = queryService.getCurrentMonthUsage(tenantId)

        val avgResponseTime = jdbcTemplate.queryForObject(
            """SELECT COALESCE(AVG(duration_ms), 0)::BIGINT
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ?""",
            Long::class.java,
            tenantId, Timestamp.from(thirtyDaysAgo), Timestamp.from(now)
        ) ?: 0

        return OverviewDashboard(
            totalRequests = usage.requests,
            successRate = successRate,
            avgResponseTimeMs = avgResponseTime,
            apdexScore = apdex.score,
            sloAvailability = sloStatus.availability.current,
            errorBudgetRemaining = sloStatus.errorBudget.budgetRemaining,
            monthlyCost = usage.costUsd
        )
    }

    /** 테넌트 사용량 대시보드를 생성한다. */
    fun getUsage(tenantId: String, from: Instant, to: Instant): UsageDashboard {
        val timeSeries = queryService.getRequestTimeSeries(tenantId, from, to)
        val topUsers = queryService.getTopUsers(tenantId, from, to)

        val channelDist = jdbcTemplate.query(
            """SELECT COALESCE(channel, 'unknown') AS channel, COUNT(*) AS count
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ?
               GROUP BY channel ORDER BY count DESC""",
            { rs, _ -> rs.getString("channel") to rs.getLong("count") },
            tenantId, Timestamp.from(from), Timestamp.from(to)
        ).toMap()

        return UsageDashboard(
            timeSeries = timeSeries,
            channelDistribution = channelDist,
            topUsers = topUsers
        )
    }

    /** 테넌트 품질 대시보드를 생성한다. */
    fun getQuality(tenantId: String, from: Instant, to: Instant): QualityDashboard {
        val percentiles = queryService.getLatencyPercentiles(tenantId, from, to)
        val errorDist = queryService.getErrorDistribution(tenantId, from, to)

        return QualityDashboard(
            latencyP50 = percentiles["p50"] ?: 0,
            latencyP95 = percentiles["p95"] ?: 0,
            latencyP99 = percentiles["p99"] ?: 0,
            errorDistribution = errorDist
        )
    }

    /** 테넌트 도구 대시보드를 생성한다. */
    fun getTools(tenantId: String, from: Instant, to: Instant): ToolDashboard {
        val ranking = queryService.getToolRanking(tenantId, from, to)
        val slowest = ranking.sortedByDescending { it.p95DurationMs }.take(5)

        return ToolDashboard(
            toolRanking = ranking,
            slowestTools = slowest
        )
    }

    /** 테넌트 비용 대시보드를 생성한다. */
    fun getCost(tenantId: String, from: Instant, to: Instant): CostDashboard {
        val usage = queryService.getCurrentMonthUsage(tenantId)

        val costByModel = jdbcTemplate.query(
            """SELECT model, SUM(estimated_cost_usd) AS cost
               FROM metric_token_usage
               WHERE tenant_id = ? AND time >= ? AND time < ?
               GROUP BY model ORDER BY cost DESC""",
            { rs, _ -> rs.getString("model") to rs.getBigDecimal("cost") },
            tenantId, Timestamp.from(from), Timestamp.from(to)
        ).toMap()

        return CostDashboard(
            monthlyCost = usage.costUsd,
            costByModel = costByModel
        )
    }
}
