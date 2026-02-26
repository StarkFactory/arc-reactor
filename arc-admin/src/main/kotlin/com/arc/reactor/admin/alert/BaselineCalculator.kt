package com.arc.reactor.admin.alert

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

data class Baseline(
    val mean: Double,
    val stdDev: Double,
    val sampleCount: Long
)

class BaselineCalculator(private val jdbcTemplate: JdbcTemplate) {

    private val cache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofHours(1))
        .build<String, Baseline>()

    fun getBaseline(tenantId: String, metric: String): Baseline? {
        val key = "$tenantId:$metric"
        return cache.getIfPresent(key) ?: computeBaseline(tenantId, metric)?.also { cache.put(key, it) }
    }

    private fun computeBaseline(tenantId: String, metric: String): Baseline? {
        val now = Instant.now()
        val sevenDaysAgo = now.minus(7, ChronoUnit.DAYS)

        return when (metric) {
            "hourly_cost" -> computeCostBaseline(tenantId, sevenDaysAgo, now)
            "hourly_requests" -> computeRequestBaseline(tenantId, sevenDaysAgo, now)
            "error_rate" -> computeErrorRateBaseline(tenantId, sevenDaysAgo, now)
            else -> null
        }
    }

    private fun computeCostBaseline(tenantId: String, from: Instant, to: Instant): Baseline? {
        val result = jdbcTemplate.queryForMap(
            """SELECT
                AVG(total_cost_usd) AS mean,
                STDDEV(total_cost_usd) AS stddev,
                COUNT(*) AS samples
               FROM metric_executions_hourly
               WHERE tenant_id = ? AND bucket >= ? AND bucket < ?""",
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
        return toBaseline(result, minSamples = 24)
    }

    private fun computeRequestBaseline(tenantId: String, from: Instant, to: Instant): Baseline? {
        val result = jdbcTemplate.queryForMap(
            """SELECT
                AVG(total_requests) AS mean,
                STDDEV(total_requests) AS stddev,
                COUNT(*) AS samples
               FROM metric_executions_hourly
               WHERE tenant_id = ? AND bucket >= ? AND bucket < ?""",
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
        return toBaseline(result, minSamples = 24)
    }

    private fun computeErrorRateBaseline(tenantId: String, from: Instant, to: Instant): Baseline? {
        val result = jdbcTemplate.queryForMap(
            """SELECT
                AVG(CASE WHEN total_requests > 0
                    THEN failed::DOUBLE PRECISION / total_requests ELSE 0 END) AS mean,
                STDDEV(CASE WHEN total_requests > 0
                    THEN failed::DOUBLE PRECISION / total_requests ELSE 0 END) AS stddev,
                COUNT(*) AS samples
               FROM metric_executions_hourly
               WHERE tenant_id = ? AND bucket >= ? AND bucket < ?""",
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
        return toBaseline(result, minSamples = 24)
    }

    /**
     * Safely converts a JDBC queryForMap result to a Baseline, handling null/type-mismatch.
     */
    private fun toBaseline(result: Map<String, Any?>, minSamples: Long): Baseline? {
        val samples = (result["samples"] as? Number)?.toLong() ?: 0L
        if (samples < minSamples) return null

        return Baseline(
            mean = (result["mean"] as? Number)?.toDouble() ?: 0.0,
            stdDev = (result["stddev"] as? Number)?.toDouble() ?: 0.0,
            sampleCount = samples
        )
    }
}
