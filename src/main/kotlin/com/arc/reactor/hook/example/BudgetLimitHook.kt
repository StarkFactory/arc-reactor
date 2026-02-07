package com.arc.reactor.hook.example

import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * 예산 제한 Hook (예시) — BeforeAgentStartHook + HookResult.Reject 패턴
 *
 * 사용자별 일일 요청 횟수를 제한합니다. 예산 초과 시 HookResult.Reject를 반환하여
 * 에이전트 실행 자체를 차단합니다.
 *
 * ## Guard vs Hook 차이
 * - **Guard**: 보안 목적 (rate limit, injection 탐지). 항상 fail-close.
 * - **Hook**: 비즈니스 로직 (예산, 승인, 감사). fail-open/fail-close 선택 가능.
 *
 * Guard의 RateLimitStage는 분/시간 단위 빈도 제한이고,
 * 이 Hook은 일일 총 사용량 제한입니다. 목적이 다릅니다.
 *
 * ## HookResult 종류
 * - `HookResult.Continue` — 계속 진행
 * - `HookResult.Reject(reason)` — 실행 차단 + 사유 반환
 * - `HookResult.Modify(params)` — 파라미터 변경 후 진행
 * - `HookResult.PendingApproval(id, msg)` — 수동 승인 대기
 *
 * ## 활성화 방법
 * @Component를 추가하고 dailyLimit을 조정하세요.
 *
 * @param dailyLimit 사용자별 일일 최대 요청 횟수
 */
// @Component  ← 주석 해제하면 자동 등록
class BudgetLimitHook(
    private val dailyLimit: Int = 100
) : BeforeAgentStartHook {

    // 1-99: 크리티컬/초기 Hook 범위. 예산 확인은 에이전트 실행 전에 해야 함
    override val order = 10

    // 예산 초과 시 에이전트 실행을 차단해야 하므로 fail-close
    override val failOnError = true

    // 사용자별 일일 사용 카운터 (실제로는 Redis나 DB를 사용)
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

    /** 일일 카운터 초기화 (스케줄러에서 자정에 호출) */
    fun resetDailyUsage() {
        dailyUsage.clear()
    }
}
