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
 * Eval 대시보드 API.
 *
 * 평가 런 목록, 합격률 추이, 테스트케이스별 점수를 조회한다.
 */
@Tag(name = "Eval Dashboard", description = "평가 결과 분석 (ADMIN)")
@RestController
@ConditionalOnProperty(
    prefix = "arc.reactor.admin", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@RequestMapping("/api/admin/evals")
class EvalDashboardController(
    private val jdbc: JdbcTemplate,
    private val tenantResolver: TenantResolver
) {

    /**
     * 평가 런 목록 조회.
     *
     * R301 fix: suspend + IO 격리. 이전 구현은 blocking JdbcTemplate.queryForList를
     * Reactor Netty 이벤트 루프에서 직접 실행했다.
     */
    @Operation(summary = "Eval 런 목록")
    @GetMapping("/runs")
    suspend fun runs(
        @RequestParam(defaultValue = "30") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
        // R301: blocking JDBC를 IO dispatcher로 격리
        val rows = withContext(Dispatchers.IO) {
            jdbc.queryForList(RUNS_SQL, tenantId, Timestamp.from(from))
        }
        return ResponseEntity.ok(rows)
    }

    /** 합격률 일별 추이. R301 fix: suspend + IO 격리. */
    @Operation(summary = "Eval 합격률 추이")
    @GetMapping("/pass-rate")
    suspend fun passRate(
        @RequestParam(defaultValue = "30") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
        // R301: blocking JDBC를 IO dispatcher로 격리
        val rows = withContext(Dispatchers.IO) {
            jdbc.queryForList(PASS_RATE_SQL, tenantId, Timestamp.from(from))
        }
        return ResponseEntity.ok(rows)
    }

    companion object {
        private const val RUNS_SQL = """
            SELECT eval_run_id,
                   COUNT(*) AS total_cases,
                   SUM(CASE WHEN pass THEN 1 ELSE 0 END) AS pass_count,
                   ROUND(AVG(score)::NUMERIC, 3) AS avg_score,
                   AVG(latency_ms)::BIGINT AS avg_latency_ms,
                   SUM(token_usage) AS total_tokens,
                   SUM(cost) AS total_cost,
                   MIN(time) AS started_at,
                   MAX(time) AS ended_at
            FROM metric_eval_results
            WHERE tenant_id = ? AND time >= ?
            GROUP BY eval_run_id
            ORDER BY MAX(time) DESC
        """

        private const val PASS_RATE_SQL = """
            SELECT DATE(time) AS day,
                   COUNT(*) AS total,
                   SUM(CASE WHEN pass THEN 1 ELSE 0 END) AS passed,
                   ROUND(AVG(score)::NUMERIC, 3) AS avg_score
            FROM metric_eval_results
            WHERE tenant_id = ? AND time >= ?
            GROUP BY DATE(time)
            ORDER BY day DESC
        """
    }
}
