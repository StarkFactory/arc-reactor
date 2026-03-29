package com.arc.reactor.admin.alert

import com.arc.reactor.admin.collection.MetricRingBuffer
import com.arc.reactor.admin.collection.PipelineHealthMonitor
import com.arc.reactor.admin.model.QuotaEvent
import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantQuota
import com.arc.reactor.admin.model.TenantStatus
import com.arc.reactor.admin.model.TenantUsage
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

    // ── 1계층: 인스턴스 로컬 카운터 (최대 10000 테넌트, 1시간 미접근 시 만료) ──
    private val localCounters = Caffeine.newBuilder()
        .maximumSize(10000)
        .expireAfterAccess(Duration.ofHours(1))
        .build<String, AtomicLong>()
    private val localCounterResetMonth = AtomicLong(currentMonth())

    // ── 2계층: Caffeine 캐시 ──
    private val usageCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofSeconds(60))
        .build<String, TenantUsage>()

    // 중복 방지: 테넌트당 월 1회만 90% 경고 발행 (최대 10000 테넌트, 1시간 만료)
    private val warnedTenants = Caffeine.newBuilder()
        .maximumSize(10000)
        .expireAfterAccess(Duration.ofHours(1))
        .build<String, Boolean>()

    override suspend fun beforeAgentStart(context: HookContext): HookResult {
        val tenantId = context.metadata["tenantId"]?.toString() ?: "default"
        if (tenantId == "default") return HookResult.Continue

        resetLocalCountersIfNewMonth()

        val localCount = localCounters
            .get(tenantId) { AtomicLong(0) }
            .incrementAndGet()

        val tenant = tenantStore.findById(tenantId) ?: return HookResult.Continue
        val rejection = checkTenantStatus(tenant)
        if (rejection != null) return rejection

        // 빠른 경로: 로컬 카운트 < 쿼터의 90%이면 즉시 통과
        if (localCount < tenant.quota.maxRequestsPerMonth * 0.9) {
            return HookResult.Continue
        }

        val usage = fetchUsageFailOpen(tenantId) ?: return HookResult.Continue
        localCounters.getIfPresent(tenantId)?.set(usage.requests)
        return enforceQuotaLimits(tenantId, tenant.quota, usage)
    }

    /** 테넌트 상태를 검증한다. 비활성 시 거부 결과를 반환한다. */
    private fun checkTenantStatus(tenant: Tenant): HookResult? {
        if (tenant.status == TenantStatus.ACTIVE) return null
        publishQuotaEvent(
            tenant.id, "rejected_suspended", 0, 0,
            "Tenant account is ${tenant.status}"
        )
        return HookResult.Reject("Tenant account is ${tenant.status}")
    }

    /** 캐시 또는 DB에서 사용량을 조회한다. 실패 시 null을 반환 (fail-open). */
    private suspend fun fetchUsageFailOpen(tenantId: String): TenantUsage? {
        return try {
            circuitBreaker.execute {
                usageCache.getIfPresent(tenantId)
                    ?: queryService.getCurrentMonthUsage(tenantId)
                        .also { usageCache.put(tenantId, it) }
            }
        } catch (e: CircuitBreakerOpenException) {
            logger.warn { "쿼터 확인 서킷 브레이커 OPEN: tenant=$tenantId, 요청 허용 (fail-open)" }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "쿼터 확인 실패: tenant=$tenantId, 요청 허용 (fail-open)" }
            null
        }
    }

    /** 쿼터 한도를 검사하고 초과 시 거부, 90% 도달 시 경고를 발행한다. */
    private fun enforceQuotaLimits(tenantId: String, quota: TenantQuota, usage: TenantUsage): HookResult {
        if (usage.requests >= quota.maxRequestsPerMonth) {
            val msg = "Monthly request quota exceeded (${usage.requests}/${quota.maxRequestsPerMonth})"
            publishQuotaEvent(tenantId, "rejected_requests", usage.requests, quota.maxRequestsPerMonth, msg)
            return HookResult.Reject(msg)
        }
        if (usage.tokens >= quota.maxTokensPerMonth) {
            publishQuotaEvent(tenantId, "rejected_tokens", usage.tokens, quota.maxTokensPerMonth, "Monthly token quota exceeded")
            return HookResult.Reject("Monthly token quota exceeded")
        }
        if (usage.requests >= quota.maxRequestsPerMonth * 0.9 && warnedTenants.asMap().putIfAbsent(tenantId, true) == null) {
            publishQuotaEvent(tenantId, "warning", usage.requests, quota.maxRequestsPerMonth, "90% quota used")
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
            localCounters.invalidateAll()
            warnedTenants.invalidateAll()
        }
    }

    private fun currentMonth(): Long {
        val ym = YearMonth.now(ZoneOffset.UTC)
        return ym.year * 100L + ym.monthValue
    }
}
