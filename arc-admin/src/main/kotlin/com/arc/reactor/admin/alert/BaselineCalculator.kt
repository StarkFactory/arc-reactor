package com.arc.reactor.admin.alert

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 메트릭의 baseline 통계 (평균, 표준편차, 샘플 수). */
data class Baseline(
    val mean: Double,
    val stdDev: Double,
    val sampleCount: Long
)

/**
 * 최근 7일 시간별 집계 데이터를 기반으로 baseline 통계를 계산한다.
 *
 * Caffeine 캐시(1시간 TTL)를 사용하여 반복 조회를 최적화한다.
 * 최소 24개 샘플이 있어야 유효한 baseline을 반환한다.
 *
 * @see AlertEvaluator BASELINE_ANOMALY 알림에서 사용
 */
class BaselineCalculator(private val jdbcTemplate: JdbcTemplate) {

    private val cache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofHours(1))
        .build<String, Baseline>()

    /** 캐시 우선 조회 후, 미스 시 DB에서 계산하여 캐시에 저장한다. */
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

    /** JDBC queryForMap 결과를 [Baseline]으로 안전하게 변환한다. null/타입 불일치를 처리한다. */
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
