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
 * Tool Call 상세 이력 API.
 *
 * 도구 호출의 인자, 결과, 개별 실행시간, 성공/실패를 조회한다.
 */
@Tag(name = "Tool Calls", description = "도구 호출 상세 이력 (ADMIN)")
@RestController
@ConditionalOnProperty(
    prefix = "arc.reactor.admin", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@RequestMapping("/api/admin/tool-calls")
class ToolCallController(
    private val jdbc: JdbcTemplate,
    private val tenantResolver: TenantResolver
) {

    /**
     * 도구 호출 이력 조회 (run_id 또는 기간 필터).
     *
     * R301 fix: suspend + IO 격리. 이전 구현은 blocking JdbcTemplate.queryForList를
     * Reactor Netty 이벤트 루프에서 직접 실행했다.
     */
    @Operation(summary = "도구 호출 이력 조회")
    @GetMapping
    suspend fun list(
        @RequestParam(required = false) runId: String?,
        @RequestParam(defaultValue = "7") days: Int,
        @RequestParam(defaultValue = "100") limit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        // R301: blocking JDBC를 IO dispatcher로 격리
        val rows = withContext(Dispatchers.IO) {
            if (!runId.isNullOrBlank()) {
                jdbc.queryForList(BY_RUN_SQL, tenantId, runId)
            } else {
                val from = Instant.now().minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
                jdbc.queryForList(BY_PERIOD_SQL, tenantId, Timestamp.from(from), limit.coerceIn(1, 500))
            }
        }
        return ResponseEntity.ok(rows)
    }

    /** 도구별 사용 랭킹. R301 fix: suspend + IO 격리. */
    @Operation(summary = "도구별 사용 통계")
    @GetMapping("/ranking")
    suspend fun ranking(
        @RequestParam(defaultValue = "30") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 365), ChronoUnit.DAYS)
        // R301: blocking JDBC를 IO dispatcher로 격리
        val rows = withContext(Dispatchers.IO) {
            jdbc.queryForList(RANKING_SQL, tenantId, Timestamp.from(from))
        }
        return ResponseEntity.ok(rows)
    }

    companion object {
        private const val BY_RUN_SQL = """
            SELECT tool_name, tool_source, mcp_server_name, call_index,
                   success, duration_ms, error_class, error_message, time
            FROM metric_tool_calls
            WHERE tenant_id = ? AND run_id = ?
            ORDER BY call_index
        """

        private const val BY_PERIOD_SQL = """
            SELECT run_id, tool_name, tool_source, mcp_server_name,
                   success, duration_ms, error_class, time
            FROM metric_tool_calls
            WHERE tenant_id = ? AND time >= ?
            ORDER BY time DESC LIMIT ?
        """

        private const val RANKING_SQL = """
            SELECT tool_name,
                   COUNT(*) AS call_count,
                   SUM(CASE WHEN success THEN 1 ELSE 0 END) AS success_count,
                   AVG(duration_ms)::BIGINT AS avg_duration_ms,
                   PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms)::BIGINT AS p95_duration_ms
            FROM metric_tool_calls
            WHERE tenant_id = ? AND time >= ?
            GROUP BY tool_name
            ORDER BY call_count DESC
        """
    }
}
