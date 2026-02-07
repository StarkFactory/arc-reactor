package com.arc.reactor.guard.example

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import java.time.LocalTime
import java.time.ZoneId

/**
 * 업무 시간 Guard (예시) — 커스텀 GuardStage 구현
 *
 * 지정된 시간대에만 에이전트 사용을 허용합니다.
 * GuardStage를 직접 구현하는 방법을 보여줍니다.
 *
 * ## Guard Pipeline 동작 원리
 * Guard는 순서대로 실행되며, 하나라도 Rejected를 반환하면 즉시 중단됩니다:
 * ```
 * [1.RateLimit] → [2.InputValidation] → [3.InjectionDetection]
 *              → [3.5 BusinessHours ← 이 Guard] → [4.Classification] → [5.Permission]
 * ```
 *
 * ## order 값 가이드
 * - 1: RateLimit (기본)
 * - 2: InputValidation (기본)
 * - 3: InjectionDetection (기본)
 * - 4: Classification (사용자 구현)
 * - 5: Permission (사용자 구현)
 * - 원하는 위치에 숫자로 삽입 가능 (예: 35 = InjectionDetection 이후)
 *
 * ## 활성화 방법
 * @Component를 추가하면 GuardPipeline이 자동으로 이 Stage를 포함합니다.
 *
 * @param startHour 업무 시작 시간 (0-23, 기본 9시)
 * @param endHour 업무 종료 시간 (0-23, 기본 18시)
 * @param timezone 시간대 (기본 Asia/Seoul)
 */
// @Component  ← 주석 해제하면 자동 등록
class BusinessHoursGuard(
    private val startHour: Int = 9,
    private val endHour: Int = 18,
    private val timezone: ZoneId = ZoneId.of("Asia/Seoul")
) : GuardStage {

    override val stageName = "BusinessHours"

    // InjectionDetection(3) 이후, Classification(4) 이전
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
