package com.arc.reactor.admin.controller

import com.arc.reactor.admin.collection.TenantResolver
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
 * 메시지별 토큰/비용 API.
 *
 * run_id(세션 턴) 단위로 prompt/completion 토큰과 비용을 조회한다.
 */
@Tag(name = "Token Cost", description = "토큰 소비/비용 분석 (ADMIN)")
@RestController
@RequestMapping("/api/admin/token-cost")
class TokenCostController(
    private val jdbc: JdbcTemplate,
    private val tenantResolver: TenantResolver
) {

    /** 세션(run_id)별 토큰/비용 내역 조회. */
    @Operation(summary = "세션별 토큰/비용 조회")
    @GetMapping("/by-session")
    fun bySession(
        @RequestParam sessionId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val rows = jdbc.queryForList(BY_SESSION_SQL, tenantId, "$sessionId%")
        return ResponseEntity.ok(rows)
    }

    /** 모델별 토큰/비용 일별 추이. */
    @Operation(summary = "모델별 일별 토큰/비용 추이")
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

    /** 비용 상위 세션 목록. */
    @Operation(summary = "비용 상위 세션 조회")
    @GetMapping("/top-expensive")
    fun topExpensive(
        @RequestParam(defaultValue = "7") days: Int,
        @RequestParam(defaultValue = "20") limit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
        val rows = jdbc.queryForList(TOP_EXPENSIVE_SQL, tenantId, Timestamp.from(from), limit.coerceIn(1, 100))
        return ResponseEntity.ok(rows)
    }

    companion object {
        private const val BY_SESSION_SQL = """
            SELECT run_id, model, provider, step_type,
                   prompt_tokens, completion_tokens, total_tokens,
                   estimated_cost_usd, time
            FROM metric_token_usage
            WHERE tenant_id = ? AND run_id LIKE ?
            ORDER BY time
        """

        private const val DAILY_SQL = """
            SELECT DATE(time) AS day, model,
                   SUM(prompt_tokens) AS prompt_tokens,
                   SUM(completion_tokens) AS completion_tokens,
                   SUM(total_tokens) AS total_tokens,
                   SUM(estimated_cost_usd) AS total_cost_usd
            FROM metric_token_usage
            WHERE tenant_id = ? AND time >= ?
            GROUP BY DATE(time), model
            ORDER BY day DESC, total_cost_usd DESC
        """

        private const val TOP_EXPENSIVE_SQL = """
            SELECT run_id,
                   SUM(total_tokens) AS total_tokens,
                   SUM(estimated_cost_usd) AS total_cost_usd,
                   MAX(model) AS model,
                   MAX(time) AS time
            FROM metric_token_usage
            WHERE tenant_id = ? AND time >= ?
            GROUP BY run_id
            ORDER BY total_cost_usd DESC
            LIMIT ?
        """
    }
}
