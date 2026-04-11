package com.arc.reactor.agent.budget

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.DoubleAdder

private val logger = KotlinLogging.logger {}

/**
 * 테넌트별 월간 비용 추적기.
 *
 * 테넌트별로 현재 월의 누적 비용(USD)을 추적하고,
 * 설정된 한도 초과 시 경고 또는 차단 상태를 반환한다.
 *
 * R314 fix: ConcurrentHashMap → Caffeine bounded cache. 기존 import만 Caffeine이었고
 * 실제 필드는 CHM이었다(불완전 마이그레이션). 이제 [maxTenants] 상한(기본 10,000)으로
 * 테넌트 수가 폭증해도 메모리 상한을 유지한다.
 *
 * @param monthlyLimitUsd 월간 한도 (0이면 무제한)
 * @param warningPercent 경고 임계치 비율 (기본 80%)
 * @param maxTenants 동시 추적할 최대 테넌트 수 (기본 10,000)
 */
class MonthlyBudgetTracker(
    private val monthlyLimitUsd: Double = 0.0,
    private val warningPercent: Int = 80,
    maxTenants: Long = DEFAULT_MAX_TENANTS
) {

    private val monthlyCosts: Cache<String, DoubleAdder> = Caffeine.newBuilder()
        .maximumSize(maxTenants)
        .build()

    /**
     * 현재 추적 중인 월.
     *
     * R319 fix: `private var currentMonth` → `AtomicReference`. 기존 구현은 plain var로
     * month rollover 시 check-then-act race가 발생했다 — 두 스레드가 동시에
     * `now != currentMonth`를 통과하여 `invalidateAll()`이 중복 실행, 그 사이 누적된
     * 첫 요청들의 비용 기록이 두 번째 invalidate에 의해 사라지는 data loss window가
     * 존재했다. `compareAndSet`으로 월 전환을 정확히 한 번만 실행한다.
     */
    private val currentMonth: AtomicReference<YearMonth> = AtomicReference(YearMonth.now())

    /** 월간 비용 상태. */
    enum class MonthlyBudgetStatus { OK, WARNING, EXCEEDED }

    /**
     * 요청 비용을 기록하고 예산 상태를 반환한다.
     *
     * @param tenantId 테넌트 ID
     * @param costUsd 이번 요청 비용 (USD)
     * @return 현재 월간 예산 상태
     */
    fun recordCost(tenantId: String, costUsd: Double): MonthlyBudgetStatus {
        resetIfNewMonth()
        // Caffeine get(key, mappingFunction)은 atomic get-or-create이며 non-null 보장
        val adder = monthlyCosts.get(tenantId) { DoubleAdder() }
        adder.add(costUsd)

        if (monthlyLimitUsd <= 0.0) return MonthlyBudgetStatus.OK

        val total = adder.sum()
        val ratio = total / monthlyLimitUsd

        return when {
            ratio >= 1.0 -> {
                logger.warn { "테넌트 $tenantId 월간 예산 초과: $${"%.4f".format(total)} / $${"%.2f".format(monthlyLimitUsd)}" }
                MonthlyBudgetStatus.EXCEEDED
            }
            ratio >= warningPercent / 100.0 -> {
                logger.warn { "테넌트 $tenantId 월간 예산 $warningPercent% 도달: $${"%.4f".format(total)} / $${"%.2f".format(monthlyLimitUsd)}" }
                MonthlyBudgetStatus.WARNING
            }
            else -> MonthlyBudgetStatus.OK
        }
    }

    /** 테넌트의 현재 월 누적 비용을 반환한다. */
    fun getCurrentCost(tenantId: String): Double {
        resetIfNewMonth()
        return monthlyCosts.getIfPresent(tenantId)?.sum() ?: 0.0
    }

    /**
     * 월이 바뀌면 카운터를 리셋한다.
     *
     * R319 fix: AtomicReference.compareAndSet으로 정확히 한 번만 리셋한다.
     * 두 스레드가 동시에 새 월을 감지해도 CAS가 실패한 쪽은 이미 리셋이 완료됐다고
     * 간주하고 무시한다. 이전 구현은 plain var 비교 후 sequential 할당이라
     * double invalidateAll()로 in-flight 카운트를 잃을 수 있었다.
     */
    private fun resetIfNewMonth() {
        val now = YearMonth.now()
        val current = currentMonth.get()
        if (now != current) {
            if (currentMonth.compareAndSet(current, now)) {
                logger.info { "월간 예산 리셋: $current → $now" }
                monthlyCosts.invalidateAll()
            }
            // CAS 실패 = 다른 스레드가 이미 리셋 완료 → 추가 작업 없음
        }
    }

    /** 테스트 전용: Caffeine 지연 maintenance를 강제 실행한다. */
    internal fun forceCleanUp() {
        monthlyCosts.cleanUp()
    }

    companion object {
        /** 기본 동시 추적 테넌트 상한. 초과 시 W-TinyLFU 정책으로 evict. */
        const val DEFAULT_MAX_TENANTS: Long = 10_000L
    }
}
