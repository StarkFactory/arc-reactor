package com.arc.reactor.admin.model

import java.time.LocalDate

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

data class SloStatus(
    val availability: SliMetric,
    val latency: SliMetric,
    val errorBudget: ErrorBudget
)

data class SliMetric(
    val name: String,
    val target: Double,
    val current: Double,
    val isHealthy: Boolean = current >= target
)

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
