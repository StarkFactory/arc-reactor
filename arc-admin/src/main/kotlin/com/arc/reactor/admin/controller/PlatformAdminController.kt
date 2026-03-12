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
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserRole
import com.arc.reactor.auth.UserStore
import com.arc.reactor.cache.ResponseCache
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import javax.sql.DataSource
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

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
    private val alertEvaluator: AlertEvaluator,
    private val userStore: UserStore,
    private val adminAuditStore: AdminAuditStore,
    private val responseCache: ResponseCache? = null
) {

    // --- Platform Health ---

    @Operation(summary = "Get platform health dashboard")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Platform health dashboard"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/health")
    fun health(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val snapshot = healthMonitor.snapshot()
        val dashboard = PlatformHealthDashboard(
            pipelineBufferUsage = snapshot.bufferUsagePercent,
            pipelineDropRate = snapshot.droppedTotal.toDouble(),
            pipelineWriteLatencyMs = snapshot.writeLatencyMs,
            activeAlerts = alertStore.findActiveAlerts().size,
            cacheExactHits = snapshot.cacheExactHitsTotal,
            cacheSemanticHits = snapshot.cacheSemanticHitsTotal,
            cacheMisses = snapshot.cacheMissesTotal
        )
        return ResponseEntity.ok(dashboard)
    }

    // --- Tenant Management ---

    @Operation(summary = "Get user by email")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "User details"),
        ApiResponse(responseCode = "400", description = "Email is required"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "User not found")
    ])
    @GetMapping("/users/by-email")
    fun getUserByEmail(
        @RequestParam email: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank()) {
            return ResponseEntity.badRequest().body(AdminErrorResponse(error = "email is required"))
        }

        val user = userStore.findByEmail(normalizedEmail) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(user.toAdminUserResponse())
    }

    @Operation(summary = "Update user role")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "User role updated"),
        ApiResponse(responseCode = "400", description = "Invalid role or invalid self-downgrade"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "User not found")
    ])
    @PostMapping("/users/{id}/role")
    fun updateUserRole(
        @PathVariable id: String,
        @jakarta.validation.Valid @RequestBody request: UpdateUserRoleRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val nextRole = parseUserRole(request.role) ?: return ResponseEntity.badRequest()
            .body(AdminErrorResponse(error = "invalid role: ${request.role}"))

        val actorId = currentActor(exchange)
        val retainsDeveloperScope = nextRole == UserRole.ADMIN || nextRole == UserRole.ADMIN_DEVELOPER
        if (actorId == id && !retainsDeveloperScope) {
            return ResponseEntity.badRequest().body(
                AdminErrorResponse(error = "cannot remove developer scope from current actor")
            )
        }

        val user = userStore.findById(id) ?: return ResponseEntity.notFound().build()
        val updated = userStore.update(user.copy(role = nextRole))
        recordAdminAudit(
            store = adminAuditStore,
            category = "platform_user",
            action = "ROLE_UPDATE",
            actor = currentActor(exchange),
            resourceType = "user",
            resourceId = id,
            detail = "role:${user.role.name}->${nextRole.name}"
        )
        return ResponseEntity.ok(updated.toAdminUserResponse())
    }

    @Operation(summary = "List all tenants")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of tenants"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/tenants")
    fun listTenants(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(tenantStore.findAll())
    }

    @Operation(summary = "Get tenant by ID")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant details"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Tenant not found")
    ])
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
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Tenant created"),
        ApiResponse(responseCode = "400", description = "Invalid request or plan"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
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
            recordAdminAudit(
                store = adminAuditStore,
                category = "platform_tenant",
                action = "CREATE",
                actor = currentActor(exchange),
                resourceType = "tenant",
                resourceId = tenant.id,
                detail = "slug=${tenant.slug};plan=${tenant.plan.name}"
            )
            ResponseEntity.status(201).body(tenant)
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "Invalid create tenant request" }
            ResponseEntity.badRequest().body(AdminErrorResponse(error = "Invalid request"))
        }
    }

    @Operation(summary = "Suspend a tenant")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant suspended"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Tenant not found")
    ])
    @PostMapping("/tenants/{id}/suspend")
    fun suspendTenant(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return try {
            val tenant = tenantService.suspend(id)
            recordAdminAudit(
                store = adminAuditStore,
                category = "platform_tenant",
                action = "SUSPEND",
                actor = currentActor(exchange),
                resourceType = "tenant",
                resourceId = tenant.id
            )
            ResponseEntity.ok(tenant)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.notFound().build()
        }
    }

    @Operation(summary = "Activate a tenant")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant activated"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Tenant not found")
    ])
    @PostMapping("/tenants/{id}/activate")
    fun activateTenant(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return try {
            val tenant = tenantService.activate(id)
            recordAdminAudit(
                store = adminAuditStore,
                category = "platform_tenant",
                action = "ACTIVATE",
                actor = currentActor(exchange),
                resourceType = "tenant",
                resourceId = tenant.id
            )
            ResponseEntity.ok(tenant)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.notFound().build()
        }
    }

    @Operation(summary = "Get tenant analytics summary")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant analytics summary"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/tenants/analytics")
    fun tenantAnalytics(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val tenants = tenantStore.findAll()
        val summaries = tenants.map { tenant ->
            val usage = try {
                queryService.getCurrentMonthUsage(tenant.id)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch usage for tenant=${tenant.id}" }
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
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of model pricing entries"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/pricing")
    fun listPricing(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(pricingStore.findAll())
    }

    @Operation(summary = "Create or update model pricing")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Model pricing saved"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping("/pricing")
    fun upsertPricing(
        @RequestBody pricing: ModelPricing,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val saved = pricingStore.save(pricing)
        recordAdminAudit(
            store = adminAuditStore,
            category = "platform_pricing",
            action = "UPSERT",
            actor = currentActor(exchange),
            resourceType = "model_pricing",
            resourceId = "${saved.provider}:${saved.model}"
        )
        return ResponseEntity.ok(saved)
    }

    @Operation(summary = "Invalidate response cache entries")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Response cache invalidated"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "500", description = "Cache invalidation failed")
    ])
    @PostMapping("/cache/invalidate")
    fun invalidateResponseCache(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val cache = responseCache
        if (cache == null) {
            return ResponseEntity.ok(
                CacheInvalidationResponse(
                    invalidated = false,
                    cacheEnabled = false,
                    message = "Response cache is disabled"
                )
            )
        }
        return try {
            cache.invalidateAll()
            recordAdminAudit(
                store = adminAuditStore,
                category = "platform_cache",
                action = "INVALIDATE_ALL",
                actor = currentActor(exchange),
                resourceType = "response_cache"
            )
            ResponseEntity.ok(
                CacheInvalidationResponse(
                    invalidated = true,
                    cacheEnabled = true,
                    message = "Response cache invalidated"
                )
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to invalidate response cache" }
            ResponseEntity.internalServerError()
                .body(AdminErrorResponse(error = "cache invalidation failed"))
        }
    }

    // --- Alert Management ---

    @Operation(summary = "List all alert rules")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of alert rules"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/alerts/rules")
    fun listAlertRules(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(alertStore.findAllRules())
    }

    @Operation(summary = "Create or update an alert rule")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Alert rule saved"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping("/alerts/rules")
    fun saveAlertRule(
        @RequestBody rule: AlertRule,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val saved = alertStore.saveRule(rule)
        recordAdminAudit(
            store = adminAuditStore,
            category = "platform_alert",
            action = "RULE_UPSERT",
            actor = currentActor(exchange),
            resourceType = "alert_rule",
            resourceId = saved.id
        )
        return ResponseEntity.ok(saved)
    }

    @Operation(summary = "Delete an alert rule")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Alert rule deleted"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Alert rule not found")
    ])
    @DeleteMapping("/alerts/rules/{id}")
    fun deleteAlertRule(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return if (alertStore.deleteRule(id)) {
            recordAdminAudit(
                store = adminAuditStore,
                category = "platform_alert",
                action = "RULE_DELETE",
                actor = currentActor(exchange),
                resourceType = "alert_rule",
                resourceId = id
            )
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @Operation(summary = "List all active alerts (platform-wide)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of active alerts"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/alerts")
    fun activeAlerts(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(alertStore.findActiveAlerts())
    }

    @Operation(summary = "Resolve an alert")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Alert resolved"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping("/alerts/{id}/resolve")
    fun resolveAlert(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        alertStore.resolveAlert(id)
        recordAdminAudit(
            store = adminAuditStore,
            category = "platform_alert",
            action = "ALERT_RESOLVE",
            actor = currentActor(exchange),
            resourceType = "alert",
            resourceId = id
        )
        return ResponseEntity.ok().build()
    }

    @Operation(summary = "Trigger alert evaluation now")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Alert evaluation completed"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping("/alerts/evaluate")
    fun evaluateAlerts(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        alertEvaluator.evaluateAll()
        recordAdminAudit(
            store = adminAuditStore,
            category = "platform_alert",
            action = "ALERT_EVALUATE",
            actor = currentActor(exchange),
            resourceType = "alert_rule_set"
        )
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

data class UpdateUserRoleRequest(
    @field:jakarta.validation.constraints.NotBlank
    val role: String
)

data class CacheInvalidationResponse(
    val invalidated: Boolean,
    val cacheEnabled: Boolean,
    val message: String
)

data class AdminUserResponse(
    val id: String,
    val email: String,
    val name: String,
    val role: String,
    val adminScope: String?,
    val createdAt: String
)

private fun User.toAdminUserResponse(): AdminUserResponse = AdminUserResponse(
    id = id,
    email = email,
    name = name,
    role = role.name,
    adminScope = role.adminScope()?.name,
    createdAt = createdAt.toString()
)

private fun parseUserRole(rawRole: String): UserRole? = try {
    UserRole.valueOf(rawRole.trim().uppercase())
} catch (_: IllegalArgumentException) {
    null
}
