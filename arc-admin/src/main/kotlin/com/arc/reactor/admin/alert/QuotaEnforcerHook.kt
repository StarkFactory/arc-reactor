package com.arc.reactor.admin.alert

import com.arc.reactor.admin.collection.PipelineHealthMonitor
import com.arc.reactor.admin.collection.TenantResolver
import com.arc.reactor.admin.model.TenantStatus
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.tenant.TenantStore
import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.resilience.CircuitBreakerOpenException
import com.arc.reactor.resilience.CircuitBreakerRegistry
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.time.Duration
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Quota enforcement with 3-layer defense:
 * 1. Local AtomicLong counter (~0ns, always succeeds)
 * 2. Caffeine cache of DB results (~0.01ms)
 * 3. DB query protected by circuit breaker (~1-5ms)
 *
 * Fail-open: if all layers fail, request is allowed with warning.
 */
class QuotaEnforcerHook(
    private val tenantResolver: TenantResolver,
    private val tenantStore: TenantStore,
    private val queryService: MetricQueryService,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    private val healthMonitor: PipelineHealthMonitor
) : BeforeAgentStartHook {

    override val order: Int = 5
    override val failOnError: Boolean = false
    override val enabled: Boolean = true

    private val circuitBreaker = circuitBreakerRegistry.get("quota-enforcer")

    // Layer 1: Instance-local counters
    private val localCounters = ConcurrentHashMap<String, AtomicLong>()
    private val localCounterResetMonth = AtomicLong(currentMonth())

    // Layer 2: Caffeine cache
    private val usageCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofSeconds(60))
        .build<String, com.arc.reactor.admin.model.TenantUsage>()

    override suspend fun beforeAgentStart(context: HookContext): HookResult {
        val tenantId = tenantResolver.currentTenantId()
        if (tenantId == "default") return HookResult.Continue

        resetLocalCountersIfNewMonth()

        // Layer 1: Local counter (always succeeds, ~0ns)
        val localCount = localCounters
            .computeIfAbsent(tenantId) { AtomicLong(0) }
            .incrementAndGet()

        val tenant = tenantStore.findById(tenantId) ?: return HookResult.Continue
        if (tenant.status != TenantStatus.ACTIVE) {
            return HookResult.Reject("Tenant account is ${tenant.status}")
        }

        val quota = tenant.quota

        // Fast path: if local count < 90% of quota, pass immediately
        if (localCount < quota.maxRequestsPerMonth * 0.9) {
            return HookResult.Continue
        }

        // Layer 2+3: Cache or DB (circuit breaker protected)
        val usage = try {
            circuitBreaker.execute {
                usageCache.getIfPresent(tenantId)
                    ?: runBlocking(Dispatchers.IO) {
                        queryService.getCurrentMonthUsage(tenantId)
                    }.also { usageCache.put(tenantId, it) }
            }
        } catch (e: CircuitBreakerOpenException) {
            logger.warn { "Quota check circuit breaker OPEN for tenant=$tenantId, allowing request" }
            return HookResult.Continue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Quota check failed for tenant=$tenantId, allowing request (fail-open)" }
            return HookResult.Continue
        }

        // Correct local counter with DB truth
        localCounters[tenantId]?.set(usage.requests)

        if (usage.requests >= quota.maxRequestsPerMonth) {
            return HookResult.Reject(
                "Monthly request quota exceeded (${usage.requests}/${quota.maxRequestsPerMonth})"
            )
        }
        if (usage.tokens >= quota.maxTokensPerMonth) {
            return HookResult.Reject("Monthly token quota exceeded")
        }

        return HookResult.Continue
    }

    private fun resetLocalCountersIfNewMonth() {
        val current = currentMonth()
        val stored = localCounterResetMonth.get()
        if (current != stored && localCounterResetMonth.compareAndSet(stored, current)) {
            localCounters.clear()
        }
    }

    private fun currentMonth(): Long {
        val ym = YearMonth.now(ZoneOffset.UTC)
        return ym.year * 100L + ym.monthValue
    }
}
