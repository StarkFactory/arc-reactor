package com.arc.reactor.admin.controller

import com.arc.reactor.admin.alert.AlertRuleStore
import com.arc.reactor.admin.collection.TenantResolver
import com.arc.reactor.admin.query.DashboardService
import com.arc.reactor.admin.query.ExportService
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.query.SloService
import com.arc.reactor.admin.tenant.TenantStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ServerWebExchange
import java.io.StringWriter
import java.time.Instant
import java.time.temporal.ChronoUnit

@Tag(name = "Tenant Admin", description = "Tenant-scoped metric dashboards and management")
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

    @Operation(summary = "Get tenant overview dashboard")
    @GetMapping("/overview")
    fun overview(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val overview = dashboardService.getOverview(tenantId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(overview)
    }

    @Operation(summary = "Get tenant usage dashboard")
    @GetMapping("/usage")
    fun usage(
        @RequestParam(required = false) fromMs: Long?,
        @RequestParam(required = false) toMs: Long?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val (from, to) = resolveTimeRange(fromMs, toMs)
        return ResponseEntity.ok(dashboardService.getUsage(tenantId, from, to))
    }

    @Operation(summary = "Get tenant quality dashboard")
    @GetMapping("/quality")
    fun quality(
        @RequestParam(required = false) fromMs: Long?,
        @RequestParam(required = false) toMs: Long?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val (from, to) = resolveTimeRange(fromMs, toMs)
        return ResponseEntity.ok(dashboardService.getQuality(tenantId, from, to))
    }

    @Operation(summary = "Get tenant tools dashboard")
    @GetMapping("/tools")
    fun tools(
        @RequestParam(required = false) fromMs: Long?,
        @RequestParam(required = false) toMs: Long?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val (from, to) = resolveTimeRange(fromMs, toMs)
        return ResponseEntity.ok(dashboardService.getTools(tenantId, from, to))
    }

    @Operation(summary = "Get tenant cost dashboard")
    @GetMapping("/cost")
    fun cost(
        @RequestParam(required = false) fromMs: Long?,
        @RequestParam(required = false) toMs: Long?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val (from, to) = resolveTimeRange(fromMs, toMs)
        return ResponseEntity.ok(dashboardService.getCost(tenantId, from, to))
    }

    @Operation(summary = "Get tenant SLO status")
    @GetMapping("/slo")
    fun slo(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val tenant = tenantStore.findById(tenantId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            sloService.getSloStatus(
                tenantId,
                tenant.sloAvailability,
                tenant.sloLatencyP99Ms
            )
        )
    }

    @Operation(summary = "List active alerts for tenant")
    @GetMapping("/alerts")
    fun alerts(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        return ResponseEntity.ok(alertStore.findActiveAlerts(tenantId))
    }

    @Operation(summary = "Get tenant current month quota usage")
    @GetMapping("/quota")
    fun quota(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenantId = tenantResolver.resolveTenantId(exchange)
        val tenant = tenantStore.findById(tenantId)
            ?: return ResponseEntity.notFound().build()
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

    @Operation(summary = "Export executions as CSV")
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
        exportService.exportExecutionsCsv(tenantId, from, to, writer)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=executions.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(writer.toString())
    }

    @Operation(summary = "Export tool calls as CSV")
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
        exportService.exportToolCallsCsv(tenantId, from, to, writer)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=tool_calls.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(writer.toString())
    }

    private fun resolveTimeRange(fromMs: Long?, toMs: Long?): Pair<Instant, Instant> {
        val to = if (toMs != null) Instant.ofEpochMilli(toMs) else Instant.now()
        val from = if (fromMs != null) Instant.ofEpochMilli(fromMs) else to.minus(30, ChronoUnit.DAYS)
        return from to to
    }
}
