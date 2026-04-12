package com.arc.reactor.admin.controller

import com.arc.reactor.admin.alert.AlertEvaluator
import com.arc.reactor.admin.alert.InMemoryAlertRuleStore
import com.arc.reactor.admin.collection.PipelineHealthMonitor
import com.arc.reactor.admin.model.AlertRule
import com.arc.reactor.admin.model.AlertType
import com.arc.reactor.admin.model.PlatformHealthDashboard
import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantPlan
import com.arc.reactor.admin.model.TenantStatus
import com.arc.reactor.admin.model.TenantUsage
import com.arc.reactor.admin.pricing.InMemoryModelPricingStore
import com.arc.reactor.admin.pricing.ModelPricing
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.tenant.InMemoryTenantStore
import com.arc.reactor.admin.tenant.TenantService
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserRole
import com.arc.reactor.auth.UserStore
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.math.BigDecimal

/** [PlatformAdminController]의 인증, 테넌트/가격/알림/캐시 관리 엔드포인트 테스트 */
class PlatformAdminControllerTest {

    private val tenantStore = InMemoryTenantStore()
    private val tenantService = mockk<TenantService>()
    private val queryService = mockk<MetricQueryService>()
    private val pricingStore = InMemoryModelPricingStore()
    private val healthMonitor = PipelineHealthMonitor()
    private val alertStore = InMemoryAlertRuleStore()
    private val alertEvaluator = mockk<AlertEvaluator>(relaxed = true)
    private val userStore = mockk<UserStore>()
    private val adminAuditStore = InMemoryAdminAuditStore()
    private val responseCache = mockk<ResponseCache>(relaxed = true)
    private val agentProperties = AgentProperties()
    private val emptyVectorStoreProvider = mockk<ObjectProvider<VectorStore>>().also {
        every { it.ifAvailable } returns null
    }

    private fun meterRegistryProvider(registry: MeterRegistry?): ObjectProvider<MeterRegistry> {
        val provider = mockk<ObjectProvider<MeterRegistry>>()
        every { provider.ifAvailable } returns registry
        return provider
    }

    private val controller = PlatformAdminController(
        tenantStore,
        tenantService,
        queryService,
        pricingStore,
        healthMonitor,
        alertStore,
        alertEvaluator,
        userStore,
        adminAuditStore,
        agentProperties,
        responseCache,
        emptyVectorStoreProvider,
        meterRegistryProvider(null)
    )

    private val testTenant = Tenant(
        id = "t1",
        name = "Test Tenant",
        slug = "test",
        plan = TenantPlan.STARTER,
        status = TenantStatus.ACTIVE
    )

    private fun exchangeWithRole(role: UserRole?): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attributes = mutableMapOf<String, Any>()
        if (role != null) {
            attributes["userRole"] = role
        }
        every { exchange.attributes } returns attributes
        return exchange
    }

    @BeforeEach
    fun setUp() {
        tenantStore.save(testTenant)
    }

    @Nested
    inner class Authentication {

        @Test
        fun `헬스 returns 403 for USER role`() {
            val response = runBlocking { controller.health(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `listTenants은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.listTenants(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `getTenant은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.getTenant("t1", exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `createTenant은(는) returns 403 for USER role`() {
            val request = CreateTenantRequest("New", "new-tenant")
            val response = runBlocking { controller.createTenant(request, exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `suspendTenant은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.suspendTenant("t1", exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `activateTenant은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.activateTenant("t1", exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `listPricing은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.listPricing(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `upsertPricing은(는) returns 403 for USER role`() {
            val pricing = ModelPricing(provider = "openai", model = "gpt-4")
            val response = runBlocking { controller.upsertPricing(pricing, exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `listAlertRules은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.listAlertRules(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `saveAlertRule은(는) returns 403 for USER role`() {
            val rule = AlertRule(name = "test", type = AlertType.STATIC_THRESHOLD, metric = "error_rate", threshold = 0.1)
            val response = runBlocking { controller.saveAlertRule(rule, exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `deleteAlertRule은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.deleteAlertRule("rule-1", exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `activeAlerts은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.activeAlerts(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `resolveAlert은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.resolveAlert("alert-1", exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `evaluateAlerts은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.evaluateAlerts(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `invalidateResponseCache은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.invalidateResponseCache(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `tenantAnalytics은(는) returns 403 for USER role`() {
            val response = runBlocking { controller.tenantAnalytics(exchangeWithRole(UserRole.USER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `listTenants은(는) returns 403 for ADMIN_MANAGER role`() {
            val response = runBlocking { controller.listTenants(exchangeWithRole(UserRole.ADMIN_MANAGER)) }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `user은(는) role update returns 403 for ADMIN_MANAGER role`() {
            val response = runBlocking {
                controller.updateUserRole(
                "u-1",
                UpdateUserRoleRequest(role = "ADMIN_DEVELOPER"),
                exchangeWithRole(UserRole.ADMIN_MANAGER)
            )
            }
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }
    }

    @Nested
    inner class PlatformHealth {

        @Test
        fun `admin에 대해 health dashboard를 반환한다`() {
            healthMonitor.updateBufferUsage(25.0)
            healthMonitor.recordWrite(100, 50)

            // R347: cache 수치는 Micrometer 카운터에서 읽는다 (PipelineHealthMonitor dead path 제거)
            val registry = SimpleMeterRegistry()
            registry.counter("arc.cache.hits", "type", "exact").increment()
            registry.counter("arc.cache.hits", "type", "semantic").increment()
            registry.counter("arc.cache.misses").increment()

            val controllerWithRegistry = PlatformAdminController(
                tenantStore,
                tenantService,
                queryService,
                pricingStore,
                healthMonitor,
                alertStore,
                alertEvaluator,
                userStore,
                adminAuditStore,
                agentProperties,
                responseCache,
                emptyVectorStoreProvider,
                meterRegistryProvider(registry)
            )

            val response = runBlocking { controllerWithRegistry.health(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            val dashboard = response.body.shouldBeInstanceOf<PlatformHealthDashboard>()
            dashboard.pipelineBufferUsage shouldBe 25.0
            dashboard.pipelineWriteLatencyMs shouldBe 50
            dashboard.cacheExactHits shouldBe 1
            dashboard.cacheSemanticHits shouldBe 1
            dashboard.cacheMisses shouldBe 1
        }

        @Test
        fun `ADMIN_MANAGER에 대해 health dashboard를 반환한다`() {
            val response = runBlocking { controller.health(exchangeWithRole(UserRole.ADMIN_MANAGER)) }
            response.statusCode shouldBe HttpStatus.OK
        }
    }

    @Nested
    inner class TenantManagement {

        @Test
        fun `listTenants은(는) returns all tenants`() {
            val response = runBlocking { controller.listTenants(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<Tenant>) shouldHaveSize 1
        }

        @Test
        fun `getTenant은(는) returns existing tenant`() {
            val response = runBlocking { controller.getTenant("t1", exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            (response.body as Tenant).id shouldBe "t1"
        }

        @Test
        fun `getTenant은(는) returns 404 for unknown`() {
            val response = runBlocking { controller.getTenant("unknown", exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        @Test
        fun `createTenant은(는) tenantService에 위임한다`() {
            every { tenantService.create(any(), any(), any()) } returns
                Tenant(name = "New", slug = "new-tenant", plan = TenantPlan.STARTER)

            val request = CreateTenantRequest("New", "new-tenant", "STARTER")
            val response = runBlocking { controller.createTenant(request, exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.CREATED
        }

        @Test
        fun `createTenant은(는) returns 400 for invalid plan`() {
            val request = CreateTenantRequest("New", "new-tenant", "INVALID_PLAN")
            val response = runBlocking { controller.createTenant(request, exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.BAD_REQUEST
        }

        @Test
        fun `tenant not found일 때 suspendTenant returns 404`() {
            every { tenantService.suspend("unknown") } throws IllegalArgumentException("Tenant not found")

            val response = runBlocking { controller.suspendTenant("unknown", exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        @Test
        fun `activateTenant은(는) returns 200`() {
            every { tenantService.activate("t1") } returns testTenant.copy(status = TenantStatus.ACTIVE)

            val response = runBlocking { controller.activateTenant("t1", exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
        }
    }

    @Nested
    inner class UserRoleManagement {

        @Test
        fun `getUserByEmail returns 400 when email은(는) blank이다`() {
            val response = runBlocking { controller.getUserByEmail("   ", exchangeWithRole(UserRole.ADMIN)) }
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
        }

        @Test
        fun `user not found일 때 getUserByEmail returns 404`() {
            every { userStore.findByEmail("missing@test.com") } returns null

            val response = runBlocking { controller.getUserByEmail("missing@test.com", exchangeWithRole(UserRole.ADMIN)) }
            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        @Test
        fun `found일 때 getUserByEmail returns user details`() {
            val user = User(
                id = "u-1",
                email = "dev@test.com",
                name = "Dev Admin",
                passwordHash = "hashed",
                role = UserRole.ADMIN_DEVELOPER
            )
            every { userStore.findByEmail("dev@test.com") } returns user

            val response = runBlocking { controller.getUserByEmail("dev@test.com", exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            val body = response.body.shouldBeInstanceOf<AdminUserResponse>()
            body.role shouldBe "ADMIN_DEVELOPER"
            body.adminScope shouldBe "DEVELOPER"
        }

        @Test
        fun `updateUserRole은(는) returns 400 for invalid role`() {
            val response = runBlocking {
                controller.updateUserRole(
                "u-1",
                UpdateUserRoleRequest(role = "INVALID_ROLE"),
                exchangeWithRole(UserRole.ADMIN)
            )
            }
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
        }

        @Test
        fun `current actor removes own developer scope일 때 updateUserRole returns 400`() {
            val exchange = mockk<ServerWebExchange>()
            every { exchange.attributes } returns mutableMapOf(
                "userRole" to UserRole.ADMIN,
                "userId" to "u-1"
            )

            val response = runBlocking {
                controller.updateUserRole(
                "u-1",
                UpdateUserRoleRequest(role = "ADMIN_MANAGER"),
                exchange
            )
            }
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
        }

        @Test
        fun `user missing일 때 updateUserRole returns 404`() {
            every { userStore.findById("missing-id") } returns null

            val response = runBlocking {
                controller.updateUserRole(
                "missing-id",
                UpdateUserRoleRequest(role = "ADMIN_MANAGER"),
                exchangeWithRole(UserRole.ADMIN)
            )
            }
            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        @Test
        fun `updateUserRole은(는) persists updated role`() {
            val user = User(
                id = "u-2",
                email = "manager@test.com",
                name = "Manager",
                passwordHash = "hashed",
                role = UserRole.USER
            )
            every { userStore.findById("u-2") } returns user
            every { userStore.update(any()) } answers { firstArg() }

            val response = runBlocking {
                controller.updateUserRole(
                "u-2",
                UpdateUserRoleRequest(role = "ADMIN_MANAGER"),
                exchangeWithRole(UserRole.ADMIN_DEVELOPER)
            )
            }

            response.statusCode shouldBe HttpStatus.OK
            val body = response.body.shouldBeInstanceOf<AdminUserResponse>()
            body.role shouldBe "ADMIN_MANAGER"
            body.adminScope shouldBe "MANAGER"

            val audit = adminAuditStore.list(limit = 1).first()
            audit.category shouldBe "platform_user"
            audit.action shouldBe "ROLE_UPDATE"
            audit.resourceType shouldBe "user"
            audit.resourceId shouldBe "u-2"
        }
    }

    @Nested
    inner class PricingManagement {

        @Test
        fun `listPricing은(는) returns all pricing entries`() {
            pricingStore.save(ModelPricing(provider = "openai", model = "gpt-4"))

            val response = runBlocking { controller.listPricing(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<*>) shouldHaveSize 1
        }

        @Test
        fun `upsertPricing은(는) saves pricing`() {
            val pricing = ModelPricing(provider = "anthropic", model = "claude-3")

            val response = runBlocking { controller.upsertPricing(pricing, exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            pricingStore.findAll() shouldHaveSize 1

            val audit = adminAuditStore.list(limit = 1).first()
            audit.category shouldBe "platform_pricing"
            audit.action shouldBe "UPSERT"
            audit.resourceType shouldBe "model_pricing"
            audit.resourceId shouldBe "anthropic:claude-3"
        }
    }

    @Nested
    inner class AlertManagement {

        @Test
        fun `listAlertRules은(는) returns all rules`() {
            alertStore.saveRule(
                AlertRule(name = "Test Rule", type = AlertType.STATIC_THRESHOLD, metric = "error_rate", threshold = 0.1)
            )

            val response = runBlocking { controller.listAlertRules(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<*>) shouldHaveSize 1
        }

        @Test
        fun `saveAlertRule은(는) creates rule`() {
            val rule = AlertRule(name = "New Rule", type = AlertType.STATIC_THRESHOLD, metric = "latency_p99", threshold = 5000.0)

            val response = runBlocking { controller.saveAlertRule(rule, exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            alertStore.findAllRules() shouldHaveSize 1
        }

        @Test
        fun `deleted일 때 deleteAlertRule returns 204`() {
            val rule = AlertRule(id = "del-1", name = "Delete Me", type = AlertType.STATIC_THRESHOLD, metric = "error_rate", threshold = 0.1)
            alertStore.saveRule(rule)

            val response = runBlocking { controller.deleteAlertRule("del-1", exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.NO_CONTENT
        }

        @Test
        fun `not found일 때 deleteAlertRule returns 404`() {
            val response = runBlocking { controller.deleteAlertRule("nonexistent", exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        @Test
        fun `activeAlerts은(는) returns empty list initially`() {
            val response = runBlocking { controller.activeAlerts(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<*>) shouldHaveSize 0
        }

        @Test
        fun `resolveAlert은(는) returns 200`() {
            val response = runBlocking { controller.resolveAlert("alert-1", exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
        }

        @Test
        fun `evaluateAlerts은(는) triggers evaluation`() {
            val response = runBlocking { controller.evaluateAlerts(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            verify { alertEvaluator.evaluateAll() }

            val audit = adminAuditStore.list(limit = 1).first()
            audit.category shouldBe "platform_alert"
            audit.action shouldBe "ALERT_EVALUATE"
        }
    }

    @Nested
    inner class CacheManagement {

        @Test
        fun `invalidateResponseCache은(는) clears cache and records audit`() {
            val response = runBlocking { controller.invalidateResponseCache(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            verify(exactly = 1) { responseCache.invalidateAll() }
            val body = response.body.shouldBeInstanceOf<CacheInvalidationResponse>()
            body.invalidated shouldBe true
            body.cacheEnabled shouldBe true

            val audit = adminAuditStore.list(limit = 1).first()
            audit.category shouldBe "platform_cache"
            audit.action shouldBe "INVALIDATE_ALL"
            audit.resourceType shouldBe "response_cache"
        }

        @Test
        fun `invalidateResponseCache returns cache disabled response when cache bean은(는) absent이다`() {
            val controllerWithoutCache = PlatformAdminController(
                tenantStore = tenantStore,
                tenantService = tenantService,
                queryService = queryService,
                pricingStore = pricingStore,
                healthMonitor = healthMonitor,
                alertStore = alertStore,
                alertEvaluator = alertEvaluator,
                userStore = userStore,
                adminAuditStore = adminAuditStore,
                agentProperties = agentProperties,
                responseCache = null,
                vectorStoreProvider = emptyVectorStoreProvider,
                meterRegistryProvider = meterRegistryProvider(null)
            )

            val response = runBlocking { controllerWithoutCache.invalidateResponseCache(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            val body = response.body.shouldBeInstanceOf<CacheInvalidationResponse>()
            body.invalidated shouldBe false
            body.cacheEnabled shouldBe false
        }

        @Test
        fun `cache fails일 때 invalidateResponseCache returns 500`() {
            val brokenCache = mockk<ResponseCache>()
            every { brokenCache.invalidateAll() } throws IllegalStateException("boom")
            val controllerWithBrokenCache = PlatformAdminController(
                tenantStore = tenantStore,
                tenantService = tenantService,
                queryService = queryService,
                pricingStore = pricingStore,
                healthMonitor = healthMonitor,
                alertStore = alertStore,
                alertEvaluator = alertEvaluator,
                userStore = userStore,
                adminAuditStore = adminAuditStore,
                agentProperties = agentProperties,
                responseCache = brokenCache,
                vectorStoreProvider = emptyVectorStoreProvider,
                meterRegistryProvider = meterRegistryProvider(null)
            )

            val response = runBlocking { controllerWithBrokenCache.invalidateResponseCache(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            response.body.shouldBeInstanceOf<AdminErrorResponse>()
        }
    }

    @Nested
    inner class TenantAnalytics {

        @Test
        fun `all tenants에 대해 analytics summary를 반환한다`() {
            every { queryService.getCurrentMonthUsage(any()) } returns
                TenantUsage("t1", 500, 25000, BigDecimal("5.00"))

            val response = runBlocking { controller.tenantAnalytics(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<*>) shouldHaveSize 1
        }

        @Test
        fun `queryService exception gracefully를 처리한다`() {
            every { queryService.getCurrentMonthUsage(any()) } throws RuntimeException("DB error")

            val response = runBlocking { controller.tenantAnalytics(exchangeWithRole(UserRole.ADMIN)) }

            response.statusCode shouldBe HttpStatus.OK
        }

        @Test
        fun `ADMIN_MANAGER에 대해 analytics summary를 반환한다`() {
            every { queryService.getCurrentMonthUsage(any()) } returns
                TenantUsage("t1", 100, 1000, BigDecimal("1.00"))

            val response = runBlocking { controller.tenantAnalytics(exchangeWithRole(UserRole.ADMIN_MANAGER)) }

            response.statusCode shouldBe HttpStatus.OK
        }
    }

    @Nested
    inner class CacheStatsFromMeterRegistry {

        @Test
        fun `cacheStats는 Micrometer 카운터에서 exact semantic miss 수치를 읽는다`() {
            val registry = SimpleMeterRegistry()
            registry.counter("arc.cache.hits", "type", "exact").increment(5.0)
            registry.counter("arc.cache.hits", "type", "semantic").increment(3.0)
            registry.counter("arc.cache.misses").increment(2.0)

            val controllerWithRegistry = PlatformAdminController(
                tenantStore,
                tenantService,
                queryService,
                pricingStore,
                healthMonitor,
                alertStore,
                alertEvaluator,
                userStore,
                adminAuditStore,
                agentProperties,
                responseCache,
                emptyVectorStoreProvider,
                meterRegistryProvider(registry)
            )

            val response = controllerWithRegistry.cacheStats(exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
            val stats = response.body.shouldBeInstanceOf<CacheStatsResponse>()
            stats.totalExactHits shouldBe 5L
            stats.totalSemanticHits shouldBe 3L
            stats.totalMisses shouldBe 2L
            stats.hitRate shouldBe 0.8
        }
    }
}
