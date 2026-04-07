package com.arc.reactor.admin.controller

import com.arc.reactor.admin.collection.TenantResolver
import com.arc.reactor.admin.query.MetricQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 레이턴시 퍼센타일 및 시계열 API.
 *
 * P50/P95/P99 퍼센타일과 시간대별 레이턴시 추이를 제공한다.
 */
@Tag(name = "Latency", description = "레이턴시 분석 (ADMIN)")
@RestController
@RequestMapping("/api/admin/metrics/latency")
class LatencyController(
    private val metricQueryService: MetricQueryService,
    private val tenantResolver: TenantResolver,
    private val jdbcTemplate: JdbcTemplate
) {

    /** P50/P95/P99 퍼센타일 조회. */
    @Operation(summary = "레이턴시 퍼센타일 조회")
    @GetMapping("/summary")
    fun summary(
        @RequestParam(defaultValue = "7") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val (from, to) = timeRange(days)
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val percentiles = metricQueryService.getLatencyPercentiles(tenantId, from, to)
        return ResponseEntity.ok(percentiles)
    }

    /** 시간대별 평균 레이턴시 시계열. */
    @Operation(summary = "레이턴시 시계열 조회")
    @GetMapping("/timeseries")
    fun timeSeries(
        @RequestParam(defaultValue = "7") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val (from, _) = timeRange(days)
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val rows = queryLatencyTimeSeries(tenantId, from)
        return ResponseEntity.ok(rows)
    }

    private fun queryLatencyTimeSeries(tenantId: String, from: Instant): List<LatencyPoint> {
        return jdbcTemplate.query(
            TIMESERIES_SQL,
            { rs, _ ->
                LatencyPoint(
                    time = rs.getString("bucket"),
                    avgMs = rs.getLong("avg_ms"),
                    p95Ms = rs.getLong("p95_ms"),
                    count = rs.getLong("cnt")
                )
            },
            tenantId, Timestamp.from(from)
        )
    }

    private fun timeRange(days: Int): Pair<Instant, Instant> {
        val to = Instant.now()
        val from = to.minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
        return from to to
    }

    companion object {
        private const val TIMESERIES_SQL = """
            SELECT DATE_TRUNC('hour', time) AS bucket,
                   AVG(duration_ms)::BIGINT AS avg_ms,
                   PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms)::BIGINT AS p95_ms,
                   COUNT(*) AS cnt
            FROM metric_agent_executions
            WHERE tenant_id = ? AND time >= ?
            GROUP BY bucket
            ORDER BY bucket
        """
    }
}

data class LatencyPoint(
    val time: String,
    val avgMs: Long,
    val p95Ms: Long,
    val count: Long
)
