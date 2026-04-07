package com.arc.reactor.admin.controller

import com.arc.reactor.admin.collection.TenantResolver
import com.arc.reactor.admin.query.MetricQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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
 * 사용자별 사용량/비용 대시보드 API.
 *
 * [MetricQueryService]를 통해 사용자별 세션 수, 토큰 소비량을 조회한다.
 */
@Tag(name = "Usage", description = "사용자별 사용량 분석 (ADMIN)")
@RestController
@RequestMapping("/api/admin/users/usage")
class UsageController(
    private val metricQueryService: MetricQueryService,
    private val tenantResolver: TenantResolver,
    private val jdbc: JdbcTemplate
) {

    /** 상위 사용자 목록 (요청 수 기준). */
    @Operation(summary = "상위 사용자 사용량 조회")
    @GetMapping("/top")
    fun topUsers(
        @RequestParam(defaultValue = "30") days: Int,
        @RequestParam(defaultValue = "20") limit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val now = Instant.now()
        val from = now.minus(days.toLong().coerceIn(1, 365), ChronoUnit.DAYS)
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val result = metricQueryService.getTopUsers(tenantId, from, now, limit.coerceIn(1, 100))
        return ResponseEntity.ok(result)
    }

    /** 사용자별 토큰/비용 상세 (metric_sessions 기반). */
    @Operation(summary = "사용자별 토큰/비용 상세")
    @GetMapping("/cost")
    fun userCost(
        @RequestParam(defaultValue = "30") days: Int,
        @RequestParam(defaultValue = "20") limit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 365), ChronoUnit.DAYS)
        val rows = jdbc.queryForList(USER_COST_SQL, tenantId, Timestamp.from(from), limit.coerceIn(1, 100))
        return ResponseEntity.ok(rows)
    }

    /** 일별 전체 사용량 추이. */
    @Operation(summary = "일별 전체 사용량 추이")
    @GetMapping("/daily")
    fun daily(
        @RequestParam(defaultValue = "30") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
        val rows = jdbc.queryForList(DAILY_SQL, tenantId, Timestamp.from(from))
        return ResponseEntity.ok(rows)
    }

    companion object {
        private const val USER_COST_SQL = """
            SELECT user_id,
                   COUNT(*) AS session_count,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(SUM(total_cost_usd), 0) AS total_cost_usd,
                   COALESCE(AVG(first_response_latency_ms), 0)::BIGINT AS avg_latency_ms,
                   MAX(time) AS last_activity
            FROM metric_sessions
            WHERE tenant_id = ? AND time >= ?
              AND user_id IS NOT NULL AND user_id <> ''
            GROUP BY user_id
            ORDER BY total_cost_usd DESC
            LIMIT ?
        """

        private const val DAILY_SQL = """
            SELECT DATE(time) AS day,
                   COUNT(*) AS session_count,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(SUM(total_cost_usd), 0) AS total_cost_usd,
                   COUNT(DISTINCT user_id) AS unique_users
            FROM metric_sessions
            WHERE tenant_id = ? AND time >= ?
            GROUP BY DATE(time)
            ORDER BY day DESC
        """
    }
}
