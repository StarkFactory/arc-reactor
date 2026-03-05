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
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserRole
import com.arc.reactor.auth.UserStore
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.math.BigDecimal

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

    private val controller = PlatformAdminController(
        tenantStore,
        tenantService,
        queryService,
        pricingStore,
        healthMonitor,
        alertStore,
        alertEvaluator,
        userStore,
        adminAuditStore
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
        fun `health returns 403 for USER role`() {
            val response = controller.health(exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `listTenants returns 403 for USER role`() {
            val response = controller.listTenants(exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `getTenant returns 403 for USER role`() {
            val response = controller.getTenant("t1", exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `createTenant returns 403 for USER role`() {
            val request = CreateTenantRequest("New", "new-tenant")
            val response = controller.createTenant(request, exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `suspendTenant returns 403 for USER role`() {
            val response = controller.suspendTenant("t1", exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `activateTenant returns 403 for USER role`() {
            val response = controller.activateTenant("t1", exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `listPricing returns 403 for USER role`() {
            val response = controller.listPricing(exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `upsertPricing returns 403 for USER role`() {
            val pricing = ModelPricing(provider = "openai", model = "gpt-4")
            val response = controller.upsertPricing(pricing, exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `listAlertRules returns 403 for USER role`() {
            val response = controller.listAlertRules(exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `saveAlertRule returns 403 for USER role`() {
            val rule = AlertRule(name = "test", type = AlertType.STATIC_THRESHOLD, metric = "error_rate", threshold = 0.1)
            val response = controller.saveAlertRule(rule, exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `deleteAlertRule returns 403 for USER role`() {
            val response = controller.deleteAlertRule("rule-1", exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `activeAlerts returns 403 for USER role`() {
            val response = controller.activeAlerts(exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `resolveAlert returns 403 for USER role`() {
            val response = controller.resolveAlert("alert-1", exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `evaluateAlerts returns 403 for USER role`() {
            val response = controller.evaluateAlerts(exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `tenantAnalytics returns 403 for USER role`() {
            val response = controller.tenantAnalytics(exchangeWithRole(UserRole.USER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `listTenants returns 403 for ADMIN_MANAGER role`() {
            val response = controller.listTenants(exchangeWithRole(UserRole.ADMIN_MANAGER))
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `user role update returns 403 for ADMIN_MANAGER role`() {
            val response = controller.updateUserRole(
                "u-1",
                UpdateUserRoleRequest(role = "ADMIN_DEVELOPER"),
                exchangeWithRole(UserRole.ADMIN_MANAGER)
            )
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }
    }

    @Nested
    inner class PlatformHealth {

        @Test
        fun `returns health dashboard for admin`() {
            healthMonitor.updateBufferUsage(25.0)
            healthMonitor.recordWrite(100, 50)

            val response = controller.health(exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
            val dashboard = response.body.shouldBeInstanceOf<PlatformHealthDashboard>()
            dashboard.pipelineBufferUsage shouldBe 25.0
            dashboard.pipelineWriteLatencyMs shouldBe 50
        }

        @Test
        fun `returns health dashboard for ADMIN_MANAGER`() {
            val response = controller.health(exchangeWithRole(UserRole.ADMIN_MANAGER))
            response.statusCode shouldBe HttpStatus.OK
        }
    }

    @Nested
    inner class TenantManagement {

        @Test
        fun `listTenants returns all tenants`() {
            val response = controller.listTenants(exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<Tenant>) shouldHaveSize 1
        }

        @Test
        fun `getTenant returns existing tenant`() {
            val response = controller.getTenant("t1", exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
            (response.body as Tenant).id shouldBe "t1"
        }

        @Test
        fun `getTenant returns 404 for unknown`() {
            val response = controller.getTenant("unknown", exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        @Test
        fun `createTenant delegates to tenantService`() {
            every { tenantService.create(any(), any(), any()) } returns
                Tenant(name = "New", slug = "new-tenant", plan = TenantPlan.STARTER)

            val request = CreateTenantRequest("New", "new-tenant", "STARTER")
            val response = controller.createTenant(request, exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.CREATED
        }

        @Test
        fun `createTenant returns 400 for invalid plan`() {
            val request = CreateTenantRequest("New", "new-tenant", "INVALID_PLAN")
            val response = controller.createTenant(request, exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.BAD_REQUEST
        }

        @Test
        fun `suspendTenant returns 404 when tenant not found`() {
            every { tenantService.suspend("unknown") } throws IllegalArgumentException("Tenant not found")

            val response = controller.suspendTenant("unknown", exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        @Test
        fun `activateTenant returns 200`() {
            every { tenantService.activate("t1") } returns testTenant.copy(status = TenantStatus.ACTIVE)

            val response = controller.activateTenant("t1", exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
        }
    }

    @Nested
    inner class UserRoleManagement {

        @Test
        fun `getUserByEmail returns 400 when email is blank`() {
            val response = controller.getUserByEmail("   ", exchangeWithRole(UserRole.ADMIN))
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
        }

        @Test
        fun `getUserByEmail returns 404 when user not found`() {
            every { userStore.findByEmail("missing@test.com") } returns null

            val response = controller.getUserByEmail("missing@test.com", exchangeWithRole(UserRole.ADMIN))
            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        @Test
        fun `getUserByEmail returns user details when found`() {
            val user = User(
                id = "u-1",
                email = "dev@test.com",
                name = "Dev Admin",
                passwordHash = "hashed",
                role = UserRole.ADMIN_DEVELOPER
            )
            every { userStore.findByEmail("dev@test.com") } returns user

            val response = controller.getUserByEmail("dev@test.com", exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
            val body = response.body.shouldBeInstanceOf<AdminUserResponse>()
            body.role shouldBe "ADMIN_DEVELOPER"
            body.adminScope shouldBe "DEVELOPER"
        }

        @Test
        fun `updateUserRole returns 400 for invalid role`() {
            val response = controller.updateUserRole(
                "u-1",
                UpdateUserRoleRequest(role = "INVALID_ROLE"),
                exchangeWithRole(UserRole.ADMIN)
            )
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
        }

        @Test
        fun `updateUserRole returns 400 when current actor removes own developer scope`() {
            val exchange = mockk<ServerWebExchange>()
            every { exchange.attributes } returns mutableMapOf(
                "userRole" to UserRole.ADMIN,
                "userId" to "u-1"
            )

            val response = controller.updateUserRole(
                "u-1",
                UpdateUserRoleRequest(role = "ADMIN_MANAGER"),
                exchange
            )
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
        }

        @Test
        fun `updateUserRole returns 404 when user missing`() {
            every { userStore.findById("missing-id") } returns null

            val response = controller.updateUserRole(
                "missing-id",
                UpdateUserRoleRequest(role = "ADMIN_MANAGER"),
                exchangeWithRole(UserRole.ADMIN)
            )
            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        @Test
        fun `updateUserRole persists updated role`() {
            val user = User(
                id = "u-2",
                email = "manager@test.com",
                name = "Manager",
                passwordHash = "hashed",
                role = UserRole.USER
            )
            every { userStore.findById("u-2") } returns user
            every { userStore.update(any()) } answers { firstArg() }

            val response = controller.updateUserRole(
                "u-2",
                UpdateUserRoleRequest(role = "ADMIN_MANAGER"),
                exchangeWithRole(UserRole.ADMIN_DEVELOPER)
            )

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
        fun `listPricing returns all pricing entries`() {
            pricingStore.save(ModelPricing(provider = "openai", model = "gpt-4"))

            val response = controller.listPricing(exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<*>) shouldHaveSize 1
        }

        @Test
        fun `upsertPricing saves pricing`() {
            val pricing = ModelPricing(provider = "anthropic", model = "claude-3")

            val response = controller.upsertPricing(pricing, exchangeWithRole(UserRole.ADMIN))

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
        fun `listAlertRules returns all rules`() {
            alertStore.saveRule(
                AlertRule(name = "Test Rule", type = AlertType.STATIC_THRESHOLD, metric = "error_rate", threshold = 0.1)
            )

            val response = controller.listAlertRules(exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<*>) shouldHaveSize 1
        }

        @Test
        fun `saveAlertRule creates rule`() {
            val rule = AlertRule(name = "New Rule", type = AlertType.STATIC_THRESHOLD, metric = "latency_p99", threshold = 5000.0)

            val response = controller.saveAlertRule(rule, exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
            alertStore.findAllRules() shouldHaveSize 1
        }

        @Test
        fun `deleteAlertRule returns 204 when deleted`() {
            val rule = AlertRule(id = "del-1", name = "Delete Me", type = AlertType.STATIC_THRESHOLD, metric = "error_rate", threshold = 0.1)
            alertStore.saveRule(rule)

            val response = controller.deleteAlertRule("del-1", exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.NO_CONTENT
        }

        @Test
        fun `deleteAlertRule returns 404 when not found`() {
            val response = controller.deleteAlertRule("nonexistent", exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        @Test
        fun `activeAlerts returns empty list initially`() {
            val response = controller.activeAlerts(exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<*>) shouldHaveSize 0
        }

        @Test
        fun `resolveAlert returns 200`() {
            val response = controller.resolveAlert("alert-1", exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
        }

        @Test
        fun `evaluateAlerts triggers evaluation`() {
            val response = controller.evaluateAlerts(exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
            verify { alertEvaluator.evaluateAll() }

            val audit = adminAuditStore.list(limit = 1).first()
            audit.category shouldBe "platform_alert"
            audit.action shouldBe "ALERT_EVALUATE"
        }
    }

    @Nested
    inner class TenantAnalytics {

        @Test
        fun `returns analytics summary for all tenants`() {
            every { queryService.getCurrentMonthUsage(any()) } returns
                TenantUsage("t1", 500, 25000, BigDecimal("5.00"))

            val response = controller.tenantAnalytics(exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
            @Suppress("UNCHECKED_CAST")
            (response.body as List<*>) shouldHaveSize 1
        }

        @Test
        fun `handles queryService exception gracefully`() {
            every { queryService.getCurrentMonthUsage(any()) } throws RuntimeException("DB error")

            val response = controller.tenantAnalytics(exchangeWithRole(UserRole.ADMIN))

            response.statusCode shouldBe HttpStatus.OK
        }

        @Test
        fun `returns analytics summary for ADMIN_MANAGER`() {
            every { queryService.getCurrentMonthUsage(any()) } returns
                TenantUsage("t1", 100, 1000, BigDecimal("1.00"))

            val response = controller.tenantAnalytics(exchangeWithRole(UserRole.ADMIN_MANAGER))

            response.statusCode shouldBe HttpStatus.OK
        }
    }
}
