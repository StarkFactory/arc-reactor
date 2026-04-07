package com.arc.reactor.admin.controller

import com.arc.reactor.admin.collection.TenantResolver
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
 * 대화 분석 API.
 *
 * 실패 패턴, 채널별 성공률, 응답 시간 분포를 제공한다.
 */
@Tag(name = "Conversation Analytics", description = "대화 분석 (ADMIN)")
@RestController
@RequestMapping("/api/admin/conversation-analytics")
class ConversationAnalyticsController(
    private val jdbc: JdbcTemplate,
    private val tenantResolver: TenantResolver
) {

    /** 채널별 성공/실패 통계. */
    @Operation(summary = "채널별 대화 성공률")
    @GetMapping("/by-channel")
    fun byChannel(
        @RequestParam(defaultValue = "30") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
        val rows = jdbc.queryForList(BY_CHANNEL_SQL, tenantId, Timestamp.from(from))
        return ResponseEntity.ok(rows)
    }

    /** 실패 원인 분포 (에러 코드별). */
    @Operation(summary = "실패 원인 분포")
    @GetMapping("/failure-patterns")
    fun failurePatterns(
        @RequestParam(defaultValue = "30") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
        val rows = jdbc.queryForList(FAILURE_SQL, tenantId, Timestamp.from(from))
        return ResponseEntity.ok(rows)
    }

    /** 응답 시간 분포 (히스토그램). */
    @Operation(summary = "응답 시간 분포")
    @GetMapping("/latency-distribution")
    fun latencyDistribution(
        @RequestParam(defaultValue = "7") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
        val rows = jdbc.queryForList(LATENCY_DIST_SQL, tenantId, Timestamp.from(from))
        return ResponseEntity.ok(rows)
    }

    companion object {
        private const val BY_CHANNEL_SQL = """
            SELECT COALESCE(channel, 'unknown') AS channel,
                   COUNT(*) AS total,
                   SUM(CASE WHEN outcome = 'resolved' THEN 1 ELSE 0 END) AS success,
                   SUM(CASE WHEN outcome != 'resolved' THEN 1 ELSE 0 END) AS failure,
                   ROUND(100.0 * SUM(CASE WHEN outcome = 'resolved' THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 1) AS success_rate,
                   AVG(total_duration_ms)::BIGINT AS avg_duration_ms
            FROM metric_sessions
            WHERE tenant_id = ? AND time >= ?
            GROUP BY channel
            ORDER BY total DESC
        """

        private const val FAILURE_SQL = """
            SELECT error_class,
                   COUNT(*) AS count,
                   MAX(time) AS latest
            FROM metric_spans
            WHERE tenant_id = ? AND time >= ? AND NOT success AND error_class IS NOT NULL
            GROUP BY error_class
            ORDER BY count DESC
            LIMIT 20
        """

        private const val LATENCY_DIST_SQL = """
            SELECT
                CASE
                    WHEN first_response_latency_ms < 1000 THEN '< 1s'
                    WHEN first_response_latency_ms < 3000 THEN '1-3s'
                    WHEN first_response_latency_ms < 5000 THEN '3-5s'
                    WHEN first_response_latency_ms < 10000 THEN '5-10s'
                    ELSE '> 10s'
                END AS bucket,
                COUNT(*) AS count
            FROM metric_sessions
            WHERE tenant_id = ? AND time >= ?
            GROUP BY bucket
            ORDER BY MIN(first_response_latency_ms)
        """
    }
}
