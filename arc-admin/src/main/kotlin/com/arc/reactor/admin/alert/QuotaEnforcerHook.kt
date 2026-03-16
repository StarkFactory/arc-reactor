package com.arc.reactor.admin.alert

import com.arc.reactor.admin.collection.MetricRingBuffer
import com.arc.reactor.admin.collection.PipelineHealthMonitor
import com.arc.reactor.admin.model.QuotaEvent
import com.arc.reactor.admin.model.TenantStatus
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.tenant.TenantStore
import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.resilience.CircuitBreakerOpenException
import com.arc.reactor.resilience.CircuitBreakerRegistry
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.time.Duration
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * 3단계 방어를 통한 쿼터 적용 Hook.
 *
 * 1. 로컬 AtomicLong 카운터 (~0ns, 항상 성공)
 * 2. Caffeine 캐시의 DB 결과 (~0.01ms)
 * 3. Circuit breaker로 보호되는 DB 쿼리 (~1-5ms)
 *
 * Fail-open: 모든 계층이 실패하면 경고와 함께 요청을 허용한다.
 *
 * @see TenantStore 테넌트 쿼터 조회
 * @see MetricQueryService 당월 사용량 조회
 */
class QuotaEnforcerHook(
    private val tenantStore: TenantStore,
    private val queryService: MetricQueryService,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    private val healthMonitor: PipelineHealthMonitor,
    private val ringBuffer: MetricRingBuffer
) : BeforeAgentStartHook {

    override val order: Int = 5
    override val failOnError: Boolean = false
    override val enabled: Boolean = true

    private val circuitBreaker = circuitBreakerRegistry.get("quota-enforcer")

    // ── 1계층: 인스턴스 로컬 카운터 ──
    private val localCounters = ConcurrentHashMap<String, AtomicLong>()
    private val localCounterResetMonth = AtomicLong(currentMonth())

    // ── 2계층: Caffeine 캐시 ──
    private val usageCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofSeconds(60))
        .build<String, com.arc.reactor.admin.model.TenantUsage>()

    // 중복 방지: 테넌트당 월 1회만 90% 경고 발행
    private val warnedTenants = ConcurrentHashMap.newKeySet<String>()

    override suspend fun beforeAgentStart(context: HookContext): HookResult {
        val tenantId = context.metadata["tenantId"]?.toString() ?: "default"
        if (tenantId == "default") return HookResult.Continue

        resetLocalCountersIfNewMonth()

        // ── 1계층: 로컬 카운터 (항상 성공, ~0ns) ──
        val localCount = localCounters
            .computeIfAbsent(tenantId) { AtomicLong(0) }
            .incrementAndGet()

        val tenant = tenantStore.findById(tenantId) ?: return HookResult.Continue
        if (tenant.status != TenantStatus.ACTIVE) {
            publishQuotaEvent(tenantId, "rejected_suspended", 0, 0, "Tenant account is ${tenant.status}")
            return HookResult.Reject("Tenant account is ${tenant.status}")
        }

        val quota = tenant.quota

        // 빠른 경로: 로컬 카운트 < 쿼터의 90%이면 즉시 통과
        if (localCount < quota.maxRequestsPerMonth * 0.9) {
            return HookResult.Continue
        }

        // ── 2+3계층: 캐시 또는 DB (circuit breaker 보호) ──
        val usage = try {
            circuitBreaker.execute {
                usageCache.getIfPresent(tenantId)
                    ?: queryService.getCurrentMonthUsage(tenantId)
                        .also { usageCache.put(tenantId, it) }
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

        // DB 실제값으로 로컬 카운터를 보정
        localCounters[tenantId]?.set(usage.requests)

        if (usage.requests >= quota.maxRequestsPerMonth) {
            publishQuotaEvent(
                tenantId, "rejected_requests", usage.requests, quota.maxRequestsPerMonth,
                "Monthly request quota exceeded (${usage.requests}/${quota.maxRequestsPerMonth})"
            )
            return HookResult.Reject(
                "Monthly request quota exceeded (${usage.requests}/${quota.maxRequestsPerMonth})"
            )
        }
        if (usage.tokens >= quota.maxTokensPerMonth) {
            publishQuotaEvent(
                tenantId, "rejected_tokens", usage.tokens, quota.maxTokensPerMonth,
                "Monthly token quota exceeded"
            )
            return HookResult.Reject("Monthly token quota exceeded")
        }

        // 90% 사용량 경고 — 테넌트당 월 1회 (중복 방지)
        if (usage.requests >= quota.maxRequestsPerMonth * 0.9 && warnedTenants.add(tenantId)) {
            publishQuotaEvent(
                tenantId, "warning", usage.requests, quota.maxRequestsPerMonth,
                "90% quota used"
            )
        }

        return HookResult.Continue
    }

    private fun publishQuotaEvent(
        tenantId: String,
        action: String,
        currentUsage: Long,
        quotaLimit: Long,
        reason: String
    ) {
        val percent = if (quotaLimit > 0) currentUsage.toDouble() / quotaLimit * 100.0 else 0.0
        val event = QuotaEvent(
            tenantId = tenantId,
            action = action,
            currentUsage = currentUsage,
            quotaLimit = quotaLimit,
            usagePercent = percent,
            reason = reason
        )
        if (!ringBuffer.publish(event)) {
            healthMonitor.recordDrop(1)
        }
    }

    private fun resetLocalCountersIfNewMonth() {
        val current = currentMonth()
        val stored = localCounterResetMonth.get()
        if (current != stored && localCounterResetMonth.compareAndSet(stored, current)) {
            localCounters.clear()
            warnedTenants.clear()
        }
    }

    private fun currentMonth(): Long {
        val ym = YearMonth.now(ZoneOffset.UTC)
        return ym.year * 100L + ym.monthValue
    }
}
