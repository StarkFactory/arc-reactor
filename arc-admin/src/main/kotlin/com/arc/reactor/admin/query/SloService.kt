package com.arc.reactor.admin.query

import com.arc.reactor.admin.model.ApdexScore
import com.arc.reactor.admin.model.ErrorBudget
import com.arc.reactor.admin.model.SliMetric
import com.arc.reactor.admin.model.SloStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * SLO(Service Level Objective) 상태와 error budget을 계산하는 서비스.
 *
 * 가용성 SLI, 지연 P99 SLI, error budget, APDEX 점수를 제공한다.
 *
 * @see DashboardService 개요 대시보드에서 SLO 상태 조회
 * @see AlertEvaluator error budget burn rate 알림 평가
 */
class SloService(
    private val jdbcTemplate: JdbcTemplate,
    private val queryService: MetricQueryService
) {

    /** 테넌트의 SLO 상태(가용성, 지연, error budget)를 계산한다. */
    fun getSloStatus(tenantId: String, sloAvailability: Double, sloLatencyP99Ms: Long): SloStatus {
        val now = Instant.now()
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)
        val successRate = queryService.getSuccessRate(tenantId, thirtyDaysAgo, now)
        val p99 = queryService.getLatencyPercentiles(tenantId, thirtyDaysAgo, now)["p99"] ?: 0
        return SloStatus(
            availability = SliMetric(name = "Availability", target = sloAvailability, current = successRate),
            latency = SliMetric(
                name = "Latency P99",
                target = sloLatencyP99Ms.toDouble(),
                current = p99.toDouble(),
                isHealthy = p99 <= sloLatencyP99Ms
            ),
            errorBudget = calculateErrorBudget(tenantId, sloAvailability, thirtyDaysAgo, now)
        )
    }

    /** 지정 기간의 error budget을 계산한다. burn rate과 예상 고갈일을 포함한다. */
    fun calculateErrorBudget(
        tenantId: String,
        sloTarget: Double,
        from: Instant,
        to: Instant
    ): ErrorBudget {
        val (total, failed) = queryFailureCounts(tenantId, from, to)
        val windowDays = ChronoUnit.DAYS.between(from, to).toInt().coerceAtLeast(1)

        if (total == 0L) {
            return ErrorBudget(sloTarget = sloTarget, windowDays = windowDays)
        }

        val budgetTotal = (total * (1 - sloTarget)).toLong().coerceAtLeast(1)
        val budgetRemaining = ((budgetTotal - failed).toDouble() / budgetTotal).coerceIn(0.0, 1.0)
        val burnRate = computeBurnRate(failed, budgetTotal, windowDays)

        return ErrorBudget(
            sloTarget = sloTarget,
            windowDays = windowDays,
            totalRequests = total,
            failedRequests = failed,
            currentAvailability = (total - failed).toDouble() / total,
            budgetTotal = budgetTotal,
            budgetConsumed = failed,
            budgetRemaining = budgetRemaining,
            burnRate = burnRate,
            projectedExhaustionDate = projectExhaustion(burnRate, budgetRemaining, windowDays)
        )
    }

    /** 지정 기간의 APDEX 점수를 계산한다 (5s satisfied, 20s tolerating 임계값). */
    fun getApdex(tenantId: String, from: Instant, to: Instant): ApdexScore {
        val result = jdbcTemplate.queryForMap(
            """SELECT
                COUNT(*) FILTER (WHERE duration_ms < 5000) AS satisfied,
                COUNT(*) FILTER (WHERE duration_ms >= 5000 AND duration_ms < 20000) AS tolerating,
                COUNT(*) FILTER (WHERE duration_ms >= 20000) AS frustrated
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ?""",
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
        return ApdexScore.calculate(
            satisfied = (result["satisfied"] as Number).toLong(),
            tolerating = (result["tolerating"] as Number).toLong(),
            frustrated = (result["frustrated"] as Number).toLong()
        )
    }

    /** 지정 기간의 전체 요청 수와 실패 수를 조회한다. */
    private fun queryFailureCounts(tenantId: String, from: Instant, to: Instant): Pair<Long, Long> {
        val result = jdbcTemplate.queryForMap(
            """SELECT
                COUNT(*) AS total,
                COUNT(*) FILTER (WHERE success = FALSE) AS failed
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ?""",
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
        return (result["total"] as Number).toLong() to (result["failed"] as Number).toLong()
    }

    /** burn rate을 계산한다. budgetTotal 또는 windowDays가 0이면 0을 반환한다. */
    private fun computeBurnRate(consumed: Long, budgetTotal: Long, windowDays: Int): Double {
        if (budgetTotal <= 0 || windowDays <= 0) return 0.0
        return (consumed.toDouble() / budgetTotal) / (windowDays.toDouble() / windowDays)
    }

    /** burn rate > 1 이고 잔여 budget이 있으면 예상 고갈일을 계산한다. */
    private fun projectExhaustion(burnRate: Double, budgetRemaining: Double, windowDays: Int): LocalDate? {
        if (burnRate <= 1.0 || budgetRemaining <= 0) return null
        val daysLeft = (budgetRemaining * windowDays / burnRate).toLong()
        return LocalDate.now(ZoneOffset.UTC).plusDays(daysLeft)
    }
}
