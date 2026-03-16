package com.arc.reactor.guard.example

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import java.time.LocalTime
import java.time.ZoneId

/**
 * 업무 시간 가드 (예제) — GuardStage 직접 구현 방식
 *
 * 지정된 시간대에만 에이전트 사용을 허용합니다.
 * GuardStage를 직접 구현하는 방법을 보여줍니다.
 *
 * ## Guard 파이프라인 동작 방식
 * Guard는 순서대로 실행되며, 하나라도 Rejected를 반환하면 즉시 중단됩니다:
 * ```
 * [1.RateLimit] → [2.InputValidation] → [3.InjectionDetection]
 *              → [3.5 BusinessHours ← 이 Guard] → [4.Classification] → [5.Permission]
 * ```
 *
 * ## Order 값 가이드
 * - 1: RateLimit (내장)
 * - 2: InputValidation (내장)
 * - 3: InjectionDetection (내장)
 * - 4: Classification (사용자 구현)
 * - 5: Permission (사용자 구현)
 * - 숫자 값으로 원하는 위치에 삽입 가능 (예: 35 = InjectionDetection 이후)
 *
 * ## 활성화 방법
 * @Component를 추가하면 GuardPipeline이 이 Stage를 자동으로 포함합니다.
 *
 * @param startHour 업무 시작 시간 (0-23, 기본값 9)
 * @param endHour 업무 종료 시간 (0-23, 기본값 18)
 * @param timezone 타임존 (기본값 Asia/Seoul)
 *
 * @see com.arc.reactor.guard.GuardStage Guard 단계 인터페이스
 * @see com.arc.reactor.guard.impl.GuardPipeline Guard 파이프라인
 */
// @Component  ← 자동 등록하려면 주석 해제
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
