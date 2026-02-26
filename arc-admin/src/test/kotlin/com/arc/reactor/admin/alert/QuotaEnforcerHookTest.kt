package com.arc.reactor.admin.alert

import com.arc.reactor.admin.collection.PipelineHealthMonitor
import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantPlan
import com.arc.reactor.admin.model.TenantQuota
import com.arc.reactor.admin.model.TenantStatus
import com.arc.reactor.admin.model.TenantUsage
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.tenant.InMemoryTenantStore
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.resilience.CircuitBreakerOpenException
import com.arc.reactor.resilience.CircuitBreakerRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class QuotaEnforcerHookTest {

    private val tenantStore = InMemoryTenantStore()
    private val queryService = mockk<MetricQueryService>()
    private val circuitBreakerRegistry = mockk<CircuitBreakerRegistry>()
    private val healthMonitor = PipelineHealthMonitor()
    private val circuitBreaker = mockk<com.arc.reactor.resilience.CircuitBreaker>()

    private lateinit var hook: QuotaEnforcerHook

    private val testTenant = Tenant(
        id = "tenant-1",
        name = "Test Corp",
        slug = "test-corp",
        plan = TenantPlan.STARTER,
        status = TenantStatus.ACTIVE,
        quota = TenantQuota(
            maxRequestsPerMonth = 100,
            maxTokensPerMonth = 10000,
            maxUsers = 5,
            maxAgents = 3,
            maxMcpServers = 2
        )
    )

    private val context = HookContext(
        runId = "run-1",
        userId = "user-1",
        userPrompt = "test prompt",
        metadata = mutableMapOf("tenantId" to "tenant-1")
    )

    @BeforeEach
    fun setup() {
        every { circuitBreakerRegistry.get("quota-enforcer") } returns circuitBreaker
        tenantStore.save(testTenant)

        hook = QuotaEnforcerHook(
            tenantStore = tenantStore,
            queryService = queryService,
            circuitBreakerRegistry = circuitBreakerRegistry,
            healthMonitor = healthMonitor
        )
    }

    @Nested
    inner class FastPath {

        @Test
        fun `default tenant bypasses quota check`() = runTest {
            val defaultCtx = HookContext(
                runId = "run-1", userId = "user-1", userPrompt = "test",
                metadata = mutableMapOf()
            )
            hook.beforeAgentStart(defaultCtx) shouldBe HookResult.Continue
        }

        @Test
        fun `first request passes immediately via local counter fast path`() = runTest {
            // Local count = 1, quota = 100, 90% threshold = 90 → fast path
            hook.beforeAgentStart(context) shouldBe HookResult.Continue
        }
    }

    @Nested
    inner class TenantStatusCheck {

        @Test
        fun `suspended tenant is rejected`() = runTest {
            tenantStore.save(testTenant.copy(status = TenantStatus.SUSPENDED))

            val result = hook.beforeAgentStart(context)
            result.shouldBeInstanceOf<HookResult.Reject>()
            result.reason shouldContain "SUSPENDED"
        }

        @Test
        fun `deactivated tenant is rejected`() = runTest {
            tenantStore.save(testTenant.copy(status = TenantStatus.DEACTIVATED))

            val result = hook.beforeAgentStart(context)
            result.shouldBeInstanceOf<HookResult.Reject>()
            result.reason shouldContain "DEACTIVATED"
        }
    }

    @Nested
    inner class QuotaEnforcement {

        @Test
        fun `circuit breaker open results in fail-open`() = runTest {
            // Set quota very low so we pass the 90% threshold
            tenantStore.save(testTenant.copy(quota = TenantQuota(maxRequestsPerMonth = 1)))

            coEvery { circuitBreaker.execute<TenantUsage>(any()) } throws CircuitBreakerOpenException("quota-enforcer")

            val result = hook.beforeAgentStart(context)
            result shouldBe HookResult.Continue
        }

        @Test
        fun `unknown exception results in fail-open`() = runTest {
            tenantStore.save(testTenant.copy(quota = TenantQuota(maxRequestsPerMonth = 1)))

            coEvery { circuitBreaker.execute<TenantUsage>(any()) } throws RuntimeException("DB down")

            val result = hook.beforeAgentStart(context)
            result shouldBe HookResult.Continue
        }
    }

    @Nested
    inner class Properties {

        @Test
        fun `hook properties are correct`() {
            hook.order shouldBe 5
            hook.failOnError shouldBe false
            hook.enabled shouldBe true
        }
    }
}
