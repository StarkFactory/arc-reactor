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

class SloService(
    private val jdbcTemplate: JdbcTemplate,
    private val queryService: MetricQueryService
) {

    fun getSloStatus(tenantId: String, sloAvailability: Double, sloLatencyP99Ms: Long): SloStatus {
        val now = Instant.now()
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)

        val successRate = queryService.getSuccessRate(tenantId, thirtyDaysAgo, now)
        val percentiles = queryService.getLatencyPercentiles(tenantId, thirtyDaysAgo, now)
        val p99 = percentiles["p99"] ?: 0

        val availability = SliMetric(
            name = "Availability",
            target = sloAvailability,
            current = successRate
        )

        val latency = SliMetric(
            name = "Latency P99",
            target = sloLatencyP99Ms.toDouble(),
            current = p99.toDouble(),
            isHealthy = p99 <= sloLatencyP99Ms
        )

        val errorBudget = calculateErrorBudget(tenantId, sloAvailability, thirtyDaysAgo, now)

        return SloStatus(
            availability = availability,
            latency = latency,
            errorBudget = errorBudget
        )
    }

    fun calculateErrorBudget(
        tenantId: String,
        sloTarget: Double,
        from: Instant,
        to: Instant
    ): ErrorBudget {
        val result = jdbcTemplate.queryForMap(
            """SELECT
                COUNT(*) AS total,
                COUNT(*) FILTER (WHERE success = FALSE) AS failed
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ?""",
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )

        val total = (result["total"] as Number).toLong()
        val failed = (result["failed"] as Number).toLong()

        if (total == 0L) {
            return ErrorBudget(
                sloTarget = sloTarget,
                windowDays = ChronoUnit.DAYS.between(from, to).toInt()
            )
        }

        val currentAvailability = (total - failed).toDouble() / total
        val budgetTotal = (total * (1 - sloTarget)).toLong().coerceAtLeast(1)
        val budgetConsumed = failed
        val budgetRemaining = ((budgetTotal - budgetConsumed).toDouble() / budgetTotal).coerceIn(0.0, 1.0)

        val windowDays = ChronoUnit.DAYS.between(from, to).toInt().coerceAtLeast(1)
        val elapsedDays = windowDays
        val burnRate = if (budgetTotal > 0 && elapsedDays > 0) {
            (budgetConsumed.toDouble() / budgetTotal) / (elapsedDays.toDouble() / windowDays)
        } else 0.0

        val projectedExhaustion = if (burnRate > 1.0 && budgetRemaining > 0) {
            val daysLeft = (budgetRemaining * windowDays / burnRate).toLong()
            LocalDate.now(ZoneOffset.UTC).plusDays(daysLeft)
        } else null

        return ErrorBudget(
            sloTarget = sloTarget,
            windowDays = windowDays,
            totalRequests = total,
            failedRequests = failed,
            currentAvailability = currentAvailability,
            budgetTotal = budgetTotal,
            budgetConsumed = budgetConsumed,
            budgetRemaining = budgetRemaining,
            burnRate = burnRate,
            projectedExhaustionDate = projectedExhaustion
        )
    }

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
}
