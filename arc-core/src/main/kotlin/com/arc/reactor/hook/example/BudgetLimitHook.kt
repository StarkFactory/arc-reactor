package com.arc.reactor.hook.example

import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * 예산 제한 Hook (예제) — BeforeAgentStartHook + HookResult.Reject 패턴
 *
 * 사용자별 일일 요청 횟수를 제한한다. 예산 초과 시 [HookResult.Reject]를 반환하여
 * 에이전트 실행 자체를 차단한다.
 *
 * ## Guard vs Hook 차이
 * - **Guard**: 보안 목적 (속도 제한, Injection 탐지). 항상 fail-close
 * - **Hook**: 비즈니스 로직 (예산, 승인, 감사). fail-open/fail-close 선택 가능
 *
 * Guard의 RateLimitStage는 분당/시간당 **빈도**를 제한하고,
 * 이 Hook은 일일 **총량**을 제한한다. 서로 다른 목적을 가진다.
 *
 * ## HookResult 유형
 * - `HookResult.Continue` — 실행 진행
 * - `HookResult.Reject(reason)` — 실행 차단 + 사유 반환
 *
 * ## 활성화 방법
 * @Component를 추가하고 dailyLimit을 조정한다.
 *
 * @param dailyLimit 사용자별 일일 최대 요청 횟수
 *
 * @see com.arc.reactor.hook.BeforeAgentStartHook 에이전트 시작 전 Hook 인터페이스
 * @see com.arc.reactor.guard.impl.DefaultRateLimitStage 비교: 빈도 기반 속도 제한
 */
// @Component  ← 자동 등록하려면 주석 해제
class BudgetLimitHook(
    private val dailyLimit: Int = 100
) : BeforeAgentStartHook {

    // 1-99: 중요/초기 Hook 범위. 예산 확인은 에이전트 실행 전에 이루어져야 함
    override val order = 10

    // 왜 fail-close인가: 예산 초과 시 에이전트 실행을 반드시 차단해야 하므로
    override val failOnError = true

    // 사용자별 일일 사용량 카운터 (프로덕션에서는 Redis 또는 DB 사용 권장)
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

    /** 일일 카운터를 초기화한다 (스케줄러에서 자정에 호출) */
    fun resetDailyUsage() {
        dailyUsage.clear()
    }
}
