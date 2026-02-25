package com.arc.reactor.admin.alert

import com.arc.reactor.admin.collection.MetricRingBuffer
import com.arc.reactor.admin.collection.PipelineHealthMonitor
import com.arc.reactor.admin.model.QuotaEvent
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
    private lateinit var ringBuffer: MetricRingBuffer

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
        ringBuffer = MetricRingBuffer(64)

        hook = QuotaEnforcerHook(
            tenantStore = tenantStore,
            queryService = queryService,
            circuitBreakerRegistry = circuitBreakerRegistry,
            healthMonitor = healthMonitor,
            ringBuffer = ringBuffer
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

    @Nested
    inner class QuotaEvents {

        @Test
        fun `publishes QuotaEvent on request quota rejection`() = runTest {
            tenantStore.save(testTenant.copy(quota = TenantQuota(maxRequestsPerMonth = 1)))

            val usage = TenantUsage(tenantId = "tenant-1", requests = 100, tokens = 0)
            coEvery { circuitBreaker.execute<TenantUsage>(any()) } returns usage

            hook.beforeAgentStart(context)

            val events = ringBuffer.drain(10)
            val quotaEvent = events.filterIsInstance<QuotaEvent>().first()
            quotaEvent.tenantId shouldBe "tenant-1"
            quotaEvent.action shouldBe "rejected_requests"
            quotaEvent.currentUsage shouldBe 100
            quotaEvent.quotaLimit shouldBe 1
            quotaEvent.reason shouldContain "request quota exceeded"
        }

        @Test
        fun `publishes QuotaEvent on token quota rejection`() = runTest {
            tenantStore.save(testTenant.copy(quota = TenantQuota(maxRequestsPerMonth = 1, maxTokensPerMonth = 10)))

            val usage = TenantUsage(tenantId = "tenant-1", requests = 0, tokens = 100)
            coEvery { circuitBreaker.execute<TenantUsage>(any()) } returns usage

            hook.beforeAgentStart(context)

            val events = ringBuffer.drain(10)
            val quotaEvent = events.filterIsInstance<QuotaEvent>().first()
            quotaEvent.action shouldBe "rejected_tokens"
            quotaEvent.reason shouldBe "Monthly token quota exceeded"
        }

        @Test
        fun `publishes QuotaEvent on suspended tenant rejection`() = runTest {
            tenantStore.save(testTenant.copy(status = TenantStatus.SUSPENDED))

            hook.beforeAgentStart(context)

            val events = ringBuffer.drain(10)
            val quotaEvent = events.filterIsInstance<QuotaEvent>().first()
            quotaEvent.action shouldBe "rejected_suspended"
            quotaEvent.reason shouldContain "SUSPENDED"
        }

        @Test
        fun `publishes warning QuotaEvent when usage reaches 90 percent`() = runTest {
            // quota=10, fast path threshold: localCount < 9. Warm up local counter to 9.
            tenantStore.save(testTenant.copy(quota = TenantQuota(maxRequestsPerMonth = 10, maxTokensPerMonth = 100000)))

            // 8 calls pass fast path (localCount 1..8, all < 9)
            repeat(8) { hook.beforeAgentStart(newContext()) }

            // 9th call: localCount=9, not < 9 → DB query. usage.requests=9 → 90% warning
            val usage = TenantUsage(tenantId = "tenant-1", requests = 9, tokens = 50)
            coEvery { circuitBreaker.execute<TenantUsage>(any()) } returns usage

            val result = hook.beforeAgentStart(newContext())
            result shouldBe HookResult.Continue

            val quotaEvents = ringBuffer.drain(10).filterIsInstance<QuotaEvent>()
            quotaEvents.size shouldBe 1
            quotaEvents[0].action shouldBe "warning"
            quotaEvents[0].currentUsage shouldBe 9
            quotaEvents[0].quotaLimit shouldBe 10
            quotaEvents[0].reason shouldBe "90% quota used"
        }

        @Test
        fun `warning is emitted only once per tenant per month (dedup)`() = runTest {
            tenantStore.save(testTenant.copy(quota = TenantQuota(maxRequestsPerMonth = 10, maxTokensPerMonth = 100000)))

            val usage = TenantUsage(tenantId = "tenant-1", requests = 9, tokens = 50)
            coEvery { circuitBreaker.execute<TenantUsage>(any()) } returns usage

            // Warm up local counter past 90% threshold (8 fast-path calls)
            repeat(8) { hook.beforeAgentStart(newContext()) }

            // 9th call → warning emitted
            hook.beforeAgentStart(newContext())
            ringBuffer.drain(10).filterIsInstance<QuotaEvent>().size shouldBe 1

            // 10th call → no duplicate warning (deduped)
            hook.beforeAgentStart(newContext())
            ringBuffer.drain(10).filterIsInstance<QuotaEvent>().size shouldBe 0
        }

        @Test
        fun `no QuotaEvent when usage is below 90 percent threshold`() = runTest {
            // With default quota (100 max), first request local count=1 < 90 → fast path, no DB query
            hook.beforeAgentStart(context)

            ringBuffer.drain(10).filterIsInstance<QuotaEvent>().size shouldBe 0
        }

        private fun newContext() = HookContext(
            runId = "run-${System.nanoTime()}",
            userId = "user-1",
            userPrompt = "test prompt",
            metadata = mutableMapOf("tenantId" to "tenant-1")
        )
    }
}
