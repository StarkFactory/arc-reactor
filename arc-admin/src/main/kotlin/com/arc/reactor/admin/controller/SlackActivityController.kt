package com.arc.reactor.admin.controller

import com.arc.reactor.admin.collection.TenantResolver
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Slack 메시지 활동 통계 API.
 *
 * metric_sessions + metric_agent_executions에서 Slack 채널별 활동을 집계한다.
 */
@Tag(name = "Slack Activity", description = "Slack 활동 통계 (ADMIN)")
@RestController
@ConditionalOnProperty(
    prefix = "arc.reactor.admin", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@RequestMapping("/api/admin/slack-activity")
class SlackActivityController(
    private val jdbc: JdbcTemplate,
    private val tenantResolver: TenantResolver
) {

    /**
     * 채널별 활동 통계.
     *
     * R301 fix: blocking JdbcTemplate.queryForList를 IO dispatcher로 격리. 이전 구현은
     * Reactor Netty 이벤트 루프에서 직접 JDBC 호출하여 워커 차단 위험.
     */
    @Operation(summary = "Slack 채널별 활동 통계")
    @GetMapping("/channels")
    suspend fun channelStats(
        @RequestParam(defaultValue = "30") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
        // R301: blocking JDBC를 IO dispatcher로 격리
        val rows = withContext(Dispatchers.IO) {
            jdbc.queryForList(CHANNEL_STATS_SQL, tenantId, Timestamp.from(from))
        }
        return ResponseEntity.ok(rows)
    }

    /** 일별 Slack 메시지 수 추이. R301 fix: suspend + IO 격리. */
    @Operation(summary = "일별 Slack 활동 추이")
    @GetMapping("/daily")
    suspend fun daily(
        @RequestParam(defaultValue = "30") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
        // R301: blocking JDBC를 IO dispatcher로 격리
        val rows = withContext(Dispatchers.IO) {
            jdbc.queryForList(DAILY_SQL, tenantId, Timestamp.from(from))
        }
        return ResponseEntity.ok(rows)
    }

    companion object {
        private const val CHANNEL_STATS_SQL = """
            SELECT channel,
                   COUNT(*) AS session_count,
                   COUNT(DISTINCT user_id) AS unique_users,
                   SUM(total_tokens) AS total_tokens,
                   SUM(total_cost_usd) AS total_cost_usd,
                   AVG(first_response_latency_ms)::BIGINT AS avg_latency_ms
            FROM metric_sessions
            WHERE tenant_id = ? AND time >= ? AND channel LIKE 'slack%'
            GROUP BY channel
            ORDER BY session_count DESC
        """

        private const val DAILY_SQL = """
            SELECT DATE(time) AS day,
                   COUNT(*) AS message_count,
                   COUNT(DISTINCT user_id) AS unique_users,
                   SUM(CASE WHEN outcome = 'resolved' THEN 1 ELSE 0 END) AS success_count,
                   SUM(CASE WHEN outcome != 'resolved' THEN 1 ELSE 0 END) AS failure_count
            FROM metric_sessions
            WHERE tenant_id = ? AND time >= ? AND channel LIKE 'slack%'
            GROUP BY DATE(time)
            ORDER BY day DESC
        """
    }
}
