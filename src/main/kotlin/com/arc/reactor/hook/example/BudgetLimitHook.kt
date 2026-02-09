package com.arc.reactor.hook.example

import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Budget Limit Hook (example) — BeforeAgentStartHook + HookResult.Reject pattern
 *
 * Limits the daily request count per user. Returns HookResult.Reject when the
 * budget is exceeded, blocking agent execution entirely.
 *
 * ## Guard vs Hook difference
 * - **Guard**: Security purposes (rate limit, injection detection). Always fail-close.
 * - **Hook**: Business logic (budget, approval, audit). Can choose fail-open/fail-close.
 *
 * Guard's RateLimitStage limits frequency per minute/hour,
 * while this Hook limits total daily usage. They serve different purposes.
 *
 * ## HookResult types
 * - `HookResult.Continue` — Proceed
 * - `HookResult.Reject(reason)` — Block execution + return reason
 *
 * ## How to activate
 * Add @Component and adjust dailyLimit as needed.
 *
 * @param dailyLimit Maximum daily request count per user
 */
// @Component  ← Uncomment to auto-register
class BudgetLimitHook(
    private val dailyLimit: Int = 100
) : BeforeAgentStartHook {

    // 1-99: Critical/early Hook range. Budget check must happen before agent execution
    override val order = 10

    // fail-close because agent execution must be blocked when budget is exceeded
    override val failOnError = true

    // Per-user daily usage counter (use Redis or DB in production)
    private val dailyUsage = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun beforeAgentStart(context: HookContext): HookResult {
        val usage = dailyUsage
            .computeIfAbsent(context.userId) { AtomicInteger(0) }
            .incrementAndGet()

        if (usage > dailyLimit) {
            logger.warn { "Budget exceeded: userId=${context.userId}, usage=$usage, limit=$dailyLimit" }
            return HookResult.Reject(
                "일일 사용 한도(${dailyLimit}회)를 초과했습니다. 내일 다시 시도해주세요."
            )
        }

        logger.debug { "Budget check passed: userId=${context.userId}, usage=$usage/$dailyLimit" }
        return HookResult.Continue
    }

    /** Reset daily counters (called at midnight by a scheduler) */
    fun resetDailyUsage() {
        dailyUsage.clear()
    }
}
