package com.arc.reactor.admin.model

import java.time.LocalDate

/** SLO error budget 상태. 소진율(burn rate)과 예상 고갈일을 포함한다. */
data class ErrorBudget(
    val sloTarget: Double,
    val windowDays: Int = 30,
    val totalRequests: Long = 0,
    val failedRequests: Long = 0,
    val currentAvailability: Double = 1.0,
    val budgetTotal: Long = 0,
    val budgetConsumed: Long = 0,
    val budgetRemaining: Double = 1.0,
    val burnRate: Double = 0.0,
    val projectedExhaustionDate: LocalDate? = null
)

/** SLO 상태. 가용성 SLI, 지연 SLI, error budget을 통합한다. */
data class SloStatus(
    val availability: SliMetric,
    val latency: SliMetric,
    val errorBudget: ErrorBudget
)

/** 단일 SLI(Service Level Indicator) 메트릭. 목표 대비 현재값과 건강 상태를 포함한다. */
data class SliMetric(
    val name: String,
    val target: Double,
    val current: Double,
    val isHealthy: Boolean = current >= target
)

/** APDEX(Application Performance Index) 점수. satisfied/tolerating/frustrated 분류를 포함한다. */
data class ApdexScore(
    val score: Double,
    val satisfied: Long = 0,
    val tolerating: Long = 0,
    val frustrated: Long = 0,
    val total: Long = 0
) {
    companion object {
        const val SATISFIED_THRESHOLD_MS = 5000L
        const val TOLERATING_THRESHOLD_MS = 20000L

        fun calculate(satisfied: Long, tolerating: Long, frustrated: Long): ApdexScore {
            val total = satisfied + tolerating + frustrated
            if (total == 0L) return ApdexScore(score = 1.0)
            val score = (satisfied + tolerating / 2.0) / total
            return ApdexScore(
                score = score,
                satisfied = satisfied,
                tolerating = tolerating,
                frustrated = frustrated,
                total = total
            )
        }
    }
}
