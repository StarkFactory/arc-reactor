package com.arc.reactor.admin.controller

import com.arc.reactor.admin.collection.TenantResolver
import com.arc.reactor.admin.query.MetricQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
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
    private val tenantResolver: TenantResolver
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
}
