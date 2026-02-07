package com.arc.reactor.guard.example

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import java.time.LocalTime
import java.time.ZoneId

/**
 * Business Hours Guard (example) — Custom GuardStage implementation
 *
 * Allows agent usage only during specified hours.
 * Demonstrates how to implement GuardStage directly.
 *
 * ## How the Guard Pipeline works
 * Guards are executed in order; if any returns Rejected, execution stops immediately:
 * ```
 * [1.RateLimit] → [2.InputValidation] → [3.InjectionDetection]
 *              → [3.5 BusinessHours ← this Guard] → [4.Classification] → [5.Permission]
 * ```
 *
 * ## Order value guide
 * - 1: RateLimit (built-in)
 * - 2: InputValidation (built-in)
 * - 3: InjectionDetection (built-in)
 * - 4: Classification (user-implemented)
 * - 5: Permission (user-implemented)
 * - Can be inserted at any position using a numeric value (e.g., 35 = after InjectionDetection)
 *
 * ## How to activate
 * Adding @Component will cause GuardPipeline to automatically include this Stage.
 *
 * @param startHour Business start hour (0-23, default 9)
 * @param endHour Business end hour (0-23, default 18)
 * @param timezone Timezone (default Asia/Seoul)
 */
// @Component  ← Uncomment to auto-register
class BusinessHoursGuard(
    private val startHour: Int = 9,
    private val endHour: Int = 18,
    private val timezone: ZoneId = ZoneId.of("Asia/Seoul")
) : GuardStage {

    override val stageName = "BusinessHours"

    // After InjectionDetection(3), before Classification(4)
    override val order = 35

    override suspend fun check(command: GuardCommand): GuardResult {
        val now = LocalTime.now(timezone)

        if (now.hour < startHour || now.hour >= endHour) {
            return GuardResult.Rejected(
                reason = "업무 시간(${startHour}시-${endHour}시)에만 이용 가능합니다. " +
                    "현재 시각: ${now.hour}시 ${now.minute}분",
                category = RejectionCategory.UNAUTHORIZED,
                stage = stageName
            )
        }

        return GuardResult.Allowed.DEFAULT
    }
}
