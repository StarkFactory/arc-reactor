package com.arc.reactor.admin.controller

import com.arc.reactor.admin.alert.AlertRuleStore
import com.arc.reactor.admin.collection.TenantResolver
import com.arc.reactor.admin.query.DashboardService
import com.arc.reactor.admin.query.ExportService
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.query.SloService
import com.arc.reactor.admin.tenant.TenantStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import mu.KotlinLogging
import java.io.StringWriter
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

/**
 * 테넌트 범위 메트릭 대시보드 및 관리 REST API 컨트롤러.
 *
 * 개요(overview), 사용량(usage), 품질(quality), 도구(tools), 비용(cost),
 * SLO, 알림, 쿼터, CSV 내보내기 등 테넌트별 분석 엔드포인트를 제공한다.
 *
 * @see PlatformAdminController 플랫폼 전역 관리 API
 * @see DashboardService 대시보드 데이터 조합 서비스
 */
@Tag(name = "Tenant Admin", description = "Tenant-scoped metric dashboards and management")
@RestController
@ConditionalOnProperty(
    prefix = "arc.reactor.admin", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@ConditionalOnBean(DataSource::class)
@RequestMapping("/api/admin/tenant")
class TenantAdminController(
    private val tenantResolver: TenantResolver,
    private val tenantStore: TenantStore,
    private val dashboardService: DashboardService,
    private val queryService: MetricQueryService,
    private val sloService: SloService,
    private val alertStore: AlertRuleStore,
    private val exportService: ExportService
) {

    /** 테넌트 개요 대시보드를 반환한다 (요청 수, 성공률, APDEX, SLO, 비용). */
    @Operation(summary = "Get tenant overview dashboard")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant overview dashboard"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Tenant not found")
    ])
    @GetMapping("/overview")
    fun overview(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val overview = dashboardService.getOverview(tenantId)
            ?: return notFoundResponse("Tenant not found")
        return ResponseEntity.ok(overview)
    }

    /** 테넌트 사용량 대시보드를 반환한다 (시계열, 채널 분포, 상위 사용자). */
    @Operation(summary = "Get tenant usage dashboard")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant usage dashboard"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/usage")
    fun usage(
        @RequestParam(required = false) fromMs: Long?,
        @RequestParam(required = false) toMs: Long?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val (from, to) = resolveTimeRange(fromMs, toMs)
        return ResponseEntity.ok(dashboardService.getUsage(tenantId, from, to))
    }

    /** 테넌트 품질 대시보드를 반환한다 (지연 백분위, 오류 분포). */
    @Operation(summary = "Get tenant quality dashboard")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant quality dashboard"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/quality")
    fun quality(
        @RequestParam(required = false) fromMs: Long?,
        @RequestParam(required = false) toMs: Long?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val (from, to) = resolveTimeRange(fromMs, toMs)
        return ResponseEntity.ok(dashboardService.getQuality(tenantId, from, to))
    }

    /** 테넌트 도구 대시보드를 반환한다 (도구 랭킹, 가장 느린 도구). */
    @Operation(summary = "Get tenant tools dashboard")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant tools dashboard"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/tools")
    fun tools(
        @RequestParam(required = false) fromMs: Long?,
        @RequestParam(required = false) toMs: Long?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val (from, to) = resolveTimeRange(fromMs, toMs)
        return ResponseEntity.ok(dashboardService.getTools(tenantId, from, to))
    }

    /** 테넌트 비용 대시보드를 반환한다 (월 비용, 모델별 비용). */
    @Operation(summary = "Get tenant cost dashboard")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant cost dashboard"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/cost")
    fun cost(
        @RequestParam(required = false) fromMs: Long?,
        @RequestParam(required = false) toMs: Long?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val (from, to) = resolveTimeRange(fromMs, toMs)
        return ResponseEntity.ok(dashboardService.getCost(tenantId, from, to))
    }

    /** 테넌트 SLO(가용성, 지연, error budget) 상태를 반환한다. */
    @Operation(summary = "Get tenant SLO status")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant SLO status"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Tenant not found")
    ])
    @GetMapping("/slo")
    fun slo(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val tenant = tenantStore.findById(tenantId)
            ?: return notFoundResponse("Tenant not found")
        return ResponseEntity.ok(
            sloService.getSloStatus(
                tenantId,
                tenant.sloAvailability,
                tenant.sloLatencyP99Ms
            )
        )
    }

    /** 해당 테넌트의 활성 알림 목록을 반환한다. */
    @Operation(summary = "List active alerts for tenant")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of active alerts for the tenant"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/alerts")
    fun alerts(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        return ResponseEntity.ok(alertStore.findActiveAlerts(tenantId))
    }

    /** 테넌트의 당월 쿼터 사용량과 제한을 반환한다. */
    @Operation(summary = "Get tenant current month quota usage")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant quota and current month usage"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Tenant not found")
    ])
    @GetMapping("/quota")
    fun quota(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val tenant = tenantStore.findById(tenantId)
            ?: return notFoundResponse("Tenant not found")
        val usage = queryService.getCurrentMonthUsage(tenantId)
        return ResponseEntity.ok(
            mapOf(
                "quota" to tenant.quota,
                "usage" to usage,
                "requestUsagePercent" to if (tenant.quota.maxRequestsPerMonth > 0)
                    usage.requests.toDouble() / tenant.quota.maxRequestsPerMonth * 100 else 0.0,
                "tokenUsagePercent" to if (tenant.quota.maxTokensPerMonth > 0)
                    usage.tokens.toDouble() / tenant.quota.maxTokensPerMonth * 100 else 0.0
            )
        )
    }

    /** 실행 이력을 CSV 파일로 내보낸다. */
    @Operation(summary = "Export executions as CSV")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "CSV file of executions"),
        ApiResponse(responseCode = "403", description = "ADMIN role required (ADMIN_DEVELOPER insufficient)")
    ])
    @GetMapping("/export/executions")
    fun exportExecutions(
        @RequestParam(required = false) fromMs: Long?,
        @RequestParam(required = false) toMs: Long?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val (from, to) = resolveTimeRange(fromMs, toMs)
        val writer = StringWriter()
        try {
            exportService.exportExecutionsCsv(tenantId, from, to, writer)
        } catch (e: Exception) {
            logger.error(e) { "CSV export failed for executions: tenant=$tenantId" }
            return ResponseEntity.internalServerError()
                .body(AdminErrorResponse(error = "CSV export failed"))
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=executions.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(writer.toString())
    }

    /** 도구 호출 이력을 CSV 파일로 내보낸다. */
    @Operation(summary = "Export tool calls as CSV")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "CSV file of tool calls"),
        ApiResponse(responseCode = "403", description = "ADMIN role required (ADMIN_DEVELOPER insufficient)")
    ])
    @GetMapping("/export/tools")
    fun exportTools(
        @RequestParam(required = false) fromMs: Long?,
        @RequestParam(required = false) toMs: Long?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val (from, to) = resolveTimeRange(fromMs, toMs)
        val writer = StringWriter()
        try {
            exportService.exportToolCallsCsv(tenantId, from, to, writer)
        } catch (e: Exception) {
            logger.error(e) { "CSV export failed for tool calls: tenant=$tenantId" }
            return ResponseEntity.internalServerError()
                .body(AdminErrorResponse(error = "CSV export failed"))
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=tool_calls.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(writer.toString())
    }

    /** 시간 범위를 파싱한다. 미지정 시 기본값: 현재 ~ 30일 전. */
    private fun resolveTimeRange(fromMs: Long?, toMs: Long?): Pair<Instant, Instant> {
        val to = if (toMs != null) Instant.ofEpochMilli(toMs) else Instant.now()
        val from = if (fromMs != null) Instant.ofEpochMilli(fromMs) else to.minus(30, ChronoUnit.DAYS)
        return from to to
    }
}
