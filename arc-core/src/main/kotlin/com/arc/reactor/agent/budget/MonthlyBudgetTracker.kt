package com.arc.reactor.agent.budget

import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder

private val logger = KotlinLogging.logger {}

/**
 * 테넌트별 월간 비용 추적기.
 *
 * 테넌트별로 현재 월의 누적 비용(USD)을 추적하고,
 * 설정된 한도 초과 시 경고 또는 차단 상태를 반환한다.
 *
 * @param monthlyLimitUsd 월간 한도 (0이면 무제한)
 * @param warningPercent 경고 임계치 비율 (기본 80%)
 */
class MonthlyBudgetTracker(
    private val monthlyLimitUsd: Double = 0.0,
    private val warningPercent: Int = 80
) {

    private val monthlyCosts = ConcurrentHashMap<String, DoubleAdder>()
    private var currentMonth: YearMonth = YearMonth.now()

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
        val adder = monthlyCosts.computeIfAbsent(tenantId) { DoubleAdder() }
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
        return monthlyCosts[tenantId]?.sum() ?: 0.0
    }

    /** 월이 바뀌면 카운터를 리셋한다. */
    private fun resetIfNewMonth() {
        val now = YearMonth.now()
        if (now != currentMonth) {
            logger.info { "월간 예산 리셋: $currentMonth → $now" }
            monthlyCosts.clear()
            currentMonth = now
        }
    }
}
