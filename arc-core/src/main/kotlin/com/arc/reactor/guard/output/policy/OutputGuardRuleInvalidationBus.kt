package com.arc.reactor.guard.output.policy

import java.util.concurrent.atomic.AtomicLong

/**
 * 동적 출력 Guard 규칙 캐시 무효화 버스
 *
 * 관리자가 규칙을 변경하면 컨트롤러가 [touch]를 호출하여 revision을 증가시킨다.
 * [com.arc.reactor.guard.output.impl.DynamicRuleOutputGuard]가 revision 변경을 감지하면
 * 주기적 갱신 간격을 기다리지 않고 즉시 규칙을 다시 로드한다.
 *
 * ## 왜 이 버스가 필요한가
 * 주기적 캐시 갱신(refreshIntervalMs)만 사용하면 규칙 변경 후
 * 최대 갱신 간격만큼 지연이 발생한다. 무효화 버스를 통해
 * 규칙 변경 사실을 즉시 전파하여 캐시 히트 시에도 재로드를 유도한다.
 *
 * ## 한계
 * 프로세스 내(in-process) 무효화만 지원한다.
 * 다중 인스턴스 환경에서는 Redis Pub/Sub 등으로 확장이 필요하다.
 *
 * @see com.arc.reactor.guard.output.impl.DynamicRuleOutputGuard 이 버스를 참조하는 Guard
 */
class OutputGuardRuleInvalidationBus {
    /** 단조 증가하는 revision 카운터 */
    private val revision = AtomicLong(0)

    /** 현재 revision 값을 반환한다 */
    fun currentRevision(): Long = revision.get()

    /** revision을 증가시켜 캐시 무효화를 신호한다. 새 revision 값을 반환한다. */
    fun touch(): Long = revision.incrementAndGet()
}
