package com.arc.reactor.admin.controller

import com.arc.reactor.admin.alert.AlertEvaluator
import com.arc.reactor.audit.recordAdminAudit
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

/**
 * 플랫폼 전역 관리 REST API 컨트롤러.
 *
 * 테넌트 CRUD, 가격 정책, 캐시 무효화, 알림 규칙 관리 등
 * 플랫폼 관리자(ADMIN) 전용 엔드포인트를 제공한다.
 *
 * `arc.reactor.admin.enabled=true`이고 [DataSource]가 존재할 때만 활성화된다.
 *
 * @see TenantAdminController 테넌트 범위 대시보드 API
 * @see MetricIngestionController 메트릭 수집 API
 */
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

    // ── 단계: 플랫폼 헬스 ──

    /** 파이프라인 버퍼, 캐시, 활성 알림 등 플랫폼 전체 상태를 반환한다. */
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

    // ── 단계: 테넌트 관리 ──

    /** 이메일로 사용자를 조회한다. */
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
            return badRequestResponse("email is required")
        }

        val user = userStore.findByEmail(normalizedEmail) ?: return notFoundResponse("User not found: $normalizedEmail")
        return ResponseEntity.ok(user.toAdminUserResponse())
    }

    /** 사용자 역할을 변경한다. 자기 자신의 개발자 권한 삭제를 방지한다. */
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

        val nextRole = parseUserRole(request.role) ?: return badRequestResponse("invalid role: ${request.role}")

        val actorId = currentActor(exchange)
        val retainsDeveloperScope = nextRole == UserRole.ADMIN || nextRole == UserRole.ADMIN_DEVELOPER
        if (actorId == id && !retainsDeveloperScope) {
            return badRequestResponse("cannot remove developer scope from current actor")
        }

        val user = userStore.findById(id) ?: return notFoundResponse("User not found: $id")
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

    /** 전체 테넌트 목록을 조회한다. */
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

    /** ID로 단일 테넌트 상세를 조회한다. */
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
            ?: return notFoundResponse("Tenant not found: $id")
        return ResponseEntity.ok(tenant)
    }

    /** 신규 테넌트를 생성한다. slug 중복 시 400을 반환한다. */
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
                return badRequestResponse("Invalid plan: ${request.plan}")
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
            badRequestResponse("Invalid request")
        }
    }

    /** 테넌트를 일시 정지한다. */
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
            notFoundResponse("Tenant not found: $id")
        }
    }

    /** 정지된 테넌트를 재활성화한다. */
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
            notFoundResponse("Tenant not found: $id")
        }
    }

    /** 전체 테넌트별 요청 수, 비용, 쿼터 사용률 요약을 반환한다. */
    @Operation(summary = "Get tenant analytics summary")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Tenant analytics summary"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/tenants/analytics")
    fun tenantAnalytics(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val tenants = tenantStore.findAll()
        // 전체 테넌트 사용량을 한 번에 조회 (N+1 → 배치 2쿼리)
        val usageByTenant = try {
            queryService.getAllTenantsCurrentMonthUsage()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch batch tenant usage" }
            emptyMap()
        }
        val summaries = tenants.map { tenant ->
            val usage = usageByTenant[tenant.id]
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

    // ── 단계: 가격 정책 관리 ──

    /** 등록된 모든 모델 가격 정책을 조회한다. */
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

    /** 모델 가격 정책을 생성하거나 갱신한다 (upsert). */
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

    /** 응답 캐시 전체를 무효화한다. 캐시 미활성 시 안내 메시지를 반환한다. */
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

    // ── 단계: 알림 관리 ──

    /** 등록된 모든 알림 규칙을 조회한다. */
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

    /** 알림 규칙을 생성하거나 갱신한다 (upsert). */
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

    /** 알림 규칙을 삭제한다. 존재하지 않으면 404를 반환한다. */
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
            notFoundResponse("Alert rule not found: $id")
        }
    }

    /** 플랫폼 전체에서 활성 상태인 알림 목록을 조회한다. */
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

    /** 활성 알림을 해결(resolve) 처리한다. */
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

    /** 알림 규칙 전체를 즉시 평가한다 (스케줄러 사이클 대기 없이). */
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

/** 테넌트 생성 요청 DTO. */
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

/** 사용자 역할 변경 요청 DTO. */
data class UpdateUserRoleRequest(
    @field:jakarta.validation.constraints.NotBlank
    val role: String
)

/** 캐시 무효화 응답 DTO. */
data class CacheInvalidationResponse(
    val invalidated: Boolean,
    val cacheEnabled: Boolean,
    val message: String
)

/** 관리자용 사용자 응답 DTO. */
data class AdminUserResponse(
    val id: String,
    val email: String,
    val name: String,
    val role: String,
    val adminScope: String?,
    val createdAt: String
)

/** [User] 도메인 객체를 관리자 응답 DTO로 변환한다. */
private fun User.toAdminUserResponse(): AdminUserResponse = AdminUserResponse(
    id = id,
    email = email,
    name = name,
    role = role.name,
    adminScope = role.adminScope()?.name,
    createdAt = createdAt.toString()
)

/** 문자열을 [UserRole] enum으로 파싱한다. 유효하지 않으면 null을 반환한다. */
private fun parseUserRole(rawRole: String): UserRole? = try {
    UserRole.valueOf(rawRole.trim().uppercase())
} catch (_: IllegalArgumentException) {
    null
}
