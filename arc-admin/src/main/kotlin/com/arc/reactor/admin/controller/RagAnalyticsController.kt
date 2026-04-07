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

/**
 * RAG 문서 분석 API.
 *
 * 문서별 검색 빈도, 후보 문서 상태 통계, stale 문서 탐지를 제공한다.
 */
@Tag(name = "RAG Analytics", description = "RAG 문서 분석 (ADMIN)")
@RestController
@RequestMapping("/api/admin/rag-analytics")
class RagAnalyticsController(
    private val jdbc: JdbcTemplate,
    private val tenantResolver: TenantResolver
) {

    /** 후보 문서 상태별 통계. */
    @Operation(summary = "RAG 후보 문서 상태 통계")
    @GetMapping("/status")
    fun statusSummary(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val rows = jdbc.queryForList(STATUS_SQL)
        return ResponseEntity.ok(rows)
    }

    /** 채널별 RAG 후보 생성 추이. */
    @Operation(summary = "채널별 RAG 후보 추이")
    @GetMapping("/by-channel")
    fun byChannel(
        @RequestParam(defaultValue = "30") days: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val rows = jdbc.queryForList(BY_CHANNEL_SQL, days)
        return ResponseEntity.ok(rows)
    }

    companion object {
        private const val STATUS_SQL = """
            SELECT status,
                   COUNT(*) AS count,
                   MAX(captured_at) AS latest_captured
            FROM rag_ingestion_candidates
            GROUP BY status
            ORDER BY count DESC
        """

        private const val BY_CHANNEL_SQL = """
            SELECT COALESCE(channel, 'unknown') AS channel,
                   COUNT(*) AS candidate_count,
                   SUM(CASE WHEN status = 'INGESTED' THEN 1 ELSE 0 END) AS ingested,
                   SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) AS pending,
                   SUM(CASE WHEN status = 'REJECTED' THEN 1 ELSE 0 END) AS rejected
            FROM rag_ingestion_candidates
            WHERE captured_at > NOW() - MAKE_INTERVAL(days => ?)
            GROUP BY channel
            ORDER BY candidate_count DESC
        """
    }
}
