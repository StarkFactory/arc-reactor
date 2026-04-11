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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 실행 트레이스 API.
 *
 * 요청의 Guard→LLM→Tool→OutputGuard 실행 흐름을 타임라인으로 조회한다.
 * metric_spans + metric_tool_calls 테이블에서 데이터를 조합한다.
 */
@Tag(name = "Traces", description = "실행 트레이스 (ADMIN)")
@RestController
@ConditionalOnProperty(
    prefix = "arc.reactor.admin", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@RequestMapping("/api/admin/traces")
class TraceController(
    private val jdbc: JdbcTemplate,
    private val tenantResolver: TenantResolver
) {

    /**
     * 최근 트레이스 목록 조회.
     *
     * R303 fix: suspend + IO 격리. 이전 구현은 blocking JdbcTemplate.queryForList를
     * Reactor Netty NIO 워커에서 직접 실행했다.
     */
    @Operation(summary = "트레이스 목록 조회")
    @GetMapping
    suspend fun list(
        @RequestParam(defaultValue = "7") days: Int,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) status: String?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val from = Instant.now().minus(days.toLong().coerceIn(1, 90), ChronoUnit.DAYS)
        // R303: blocking JDBC를 IO dispatcher로 격리
        val rows = withContext(Dispatchers.IO) {
            queryTraceList(tenantId, from, status, limit.coerceIn(1, 200))
        }
        return ResponseEntity.ok(rows)
    }

    /** 특정 트레이스의 스팬 타임라인 조회. R303 fix: suspend + IO 격리. */
    @Operation(summary = "트레이스 스팬 타임라인")
    @GetMapping("/{traceId}/spans")
    suspend fun spans(
        @PathVariable traceId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        // R303: blocking JDBC를 IO dispatcher로 격리
        val rows = withContext(Dispatchers.IO) {
            querySpans(traceId)
        }
        return ResponseEntity.ok(rows)
    }

    private fun queryTraceList(tenantId: String, from: Instant, status: String?, limit: Int): List<Map<String, Any?>> {
        val sql = buildString {
            append(TRACE_LIST_BASE)
            if (status == "error") append(" AND NOT s.success")
            append(" GROUP BY s.trace_id ORDER BY MAX(s.time) DESC LIMIT ?")
        }
        return jdbc.queryForList(sql, tenantId, Timestamp.from(from), limit)
    }

    private fun querySpans(traceId: String): List<Map<String, Any?>> {
        return jdbc.queryForList(SPAN_DETAIL_SQL, traceId)
    }

    companion object {
        private const val TRACE_LIST_BASE = """
            SELECT s.trace_id,
                   MAX(s.time) AS time,
                   SUM(s.duration_ms) AS total_duration_ms,
                   COUNT(*) AS span_count,
                   BOOL_AND(s.success) AS success,
                   MAX(s.run_id) AS run_id
            FROM metric_spans s
            WHERE s.tenant_id = ? AND s.time >= ?
        """

        private const val SPAN_DETAIL_SQL = """
            SELECT span_id, parent_span_id, operation_name, service_name,
                   duration_ms, success, error_class, attributes, time
            FROM metric_spans
            WHERE trace_id = ?
            ORDER BY time
        """
    }
}
