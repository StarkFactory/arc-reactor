package com.arc.reactor.admin.controller

import com.arc.reactor.admin.alert.AlertEvaluator
import com.arc.reactor.admin.alert.AlertRuleStore
import com.arc.reactor.admin.collection.PipelineHealthMonitor
import com.arc.reactor.admin.model.AlertRule
import com.arc.reactor.admin.model.PlatformHealthDashboard
import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantAnalyticsSummary
import com.arc.reactor.admin.pricing.ModelPricing
import com.arc.reactor.admin.pricing.ModelPricingStore
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.tenant.TenantService
import com.arc.reactor.admin.tenant.TenantStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import javax.sql.DataSource

@Tag(name = "Platform Admin", description = "Platform-wide administration APIs (ADMIN)")
@RestController
@ConditionalOnProperty(
    prefix = "arc.reactor.admin", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@ConditionalOnBean(DataSource::class)
@RequestMapping("/api/admin/platform")
class PlatformAdminController(
    private val tenantStore: TenantStore,
    private val tenantService: TenantService,
    private val queryService: MetricQueryService,
    private val pricingStore: ModelPricingStore,
    private val healthMonitor: PipelineHealthMonitor,
    private val alertStore: AlertRuleStore,
    private val alertEvaluator: AlertEvaluator
) {

    // --- Platform Health ---

    @Operation(summary = "Get platform health dashboard")
    @GetMapping("/health")
    fun health(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val snapshot = healthMonitor.snapshot()
        val dashboard = PlatformHealthDashboard(
            pipelineBufferUsage = snapshot.bufferUsagePercent,
            pipelineDropRate = snapshot.droppedTotal.toDouble(),
            pipelineWriteLatencyMs = snapshot.writeLatencyMs,
            activeAlerts = alertStore.findActiveAlerts().size
        )
        return ResponseEntity.ok(dashboard)
    }

    // --- Tenant Management ---

    @Operation(summary = "List all tenants")
    @GetMapping("/tenants")
    fun listTenants(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(tenantStore.findAll())
    }

    @Operation(summary = "Get tenant by ID")
    @GetMapping("/tenants/{id}")
    fun getTenant(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenant = tenantStore.findById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(tenant)
    }

    @Operation(summary = "Create a new tenant")
    @PostMapping("/tenants")
    fun createTenant(
        @jakarta.validation.Valid @RequestBody request: CreateTenantRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return try {
            val plan = try {
                com.arc.reactor.admin.model.TenantPlan.valueOf(request.plan.uppercase())
            } catch (_: IllegalArgumentException) {
                return ResponseEntity.badRequest().body(
                    AdminErrorResponse(error = "Invalid plan: ${request.plan}")
                )
            }
            val tenant = tenantService.create(request.name, request.slug, plan)
            ResponseEntity.status(201).body(tenant)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(AdminErrorResponse(error = e.message ?: "Invalid request"))
        }
    }

    @Operation(summary = "Suspend a tenant")
    @PostMapping("/tenants/{id}/suspend")
    fun suspendTenant(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return try {
            ResponseEntity.ok(tenantService.suspend(id))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.notFound().build()
        }
    }

    @Operation(summary = "Activate a tenant")
    @PostMapping("/tenants/{id}/activate")
    fun activateTenant(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return try {
            ResponseEntity.ok(tenantService.activate(id))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.notFound().build()
        }
    }

    @Operation(summary = "Get tenant analytics summary")
    @GetMapping("/tenants/analytics")
    fun tenantAnalytics(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val tenants = tenantStore.findAll()
        val summaries = tenants.map { tenant ->
            val usage = try {
                queryService.getCurrentMonthUsage(tenant.id)
            } catch (_: Exception) {
                null
            }
            TenantAnalyticsSummary(
                tenantId = tenant.id,
                tenantName = tenant.name,
                plan = tenant.plan.name,
                requests = usage?.requests ?: 0,
                cost = usage?.costUsd ?: java.math.BigDecimal.ZERO,
                quotaUsagePercent = if (tenant.quota.maxRequestsPerMonth > 0 && usage != null)
                    usage.requests.toDouble() / tenant.quota.maxRequestsPerMonth * 100 else 0.0
            )
        }
        return ResponseEntity.ok(summaries)
    }

    // --- Pricing Management ---

    @Operation(summary = "List all model pricing entries")
    @GetMapping("/pricing")
    fun listPricing(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(pricingStore.findAll())
    }

    @Operation(summary = "Create or update model pricing")
    @PostMapping("/pricing")
    fun upsertPricing(
        @RequestBody pricing: ModelPricing,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(pricingStore.save(pricing))
    }

    // --- Alert Management ---

    @Operation(summary = "List all alert rules")
    @GetMapping("/alerts/rules")
    fun listAlertRules(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(alertStore.findAllRules())
    }

    @Operation(summary = "Create or update an alert rule")
    @PostMapping("/alerts/rules")
    fun saveAlertRule(
        @RequestBody rule: AlertRule,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(alertStore.saveRule(rule))
    }

    @Operation(summary = "Delete an alert rule")
    @DeleteMapping("/alerts/rules/{id}")
    fun deleteAlertRule(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return if (alertStore.deleteRule(id)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @Operation(summary = "List all active alerts (platform-wide)")
    @GetMapping("/alerts")
    fun activeAlerts(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(alertStore.findActiveAlerts())
    }

    @Operation(summary = "Resolve an alert")
    @PostMapping("/alerts/{id}/resolve")
    fun resolveAlert(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        alertStore.resolveAlert(id)
        return ResponseEntity.ok().build()
    }

    @Operation(summary = "Trigger alert evaluation now")
    @PostMapping("/alerts/evaluate")
    fun evaluateAlerts(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        alertEvaluator.evaluateAll()
        return ResponseEntity.ok(mapOf("status" to "evaluation complete"))
    }
}

data class CreateTenantRequest(
    @field:jakarta.validation.constraints.NotBlank
    @field:jakarta.validation.constraints.Size(min = 1, max = 200)
    val name: String,

    @field:jakarta.validation.constraints.NotBlank
    @field:jakarta.validation.constraints.Size(min = 2, max = 50)
    @field:jakarta.validation.constraints.Pattern(regexp = "^[a-z0-9][a-z0-9-]*[a-z0-9]$")
    val slug: String,

    @field:jakarta.validation.constraints.Size(max = 20)
    val plan: String = "FREE"
)
