package com.arc.reactor.support

import java.util.concurrent.atomic.AtomicInteger

/**
 * 비동기 테스트를 위한 폴링 기반 대기 유틸리티.
 *
 * `Thread.sleep(300)` 같은 고정 대기 대신 조건 기반 대기를 제공하여
 * 테스트 안정성을 높이고 실행 시간을 최소화한다.
 *
 * **주의**: `runTest` 내부에서 `Dispatchers.Default`의 부수 효과를 관찰해야 할 때는
 * 반드시 [pollUntil] / [pollCount] (실제 시간 폴링)을 사용해야 한다.
 * `kotlinx.coroutines.delay()`는 가상 시간을 사용하므로 실제 배경 작업을 기다리지 못한다.
 */
object AsyncTestSupport {

    /**
     * 조건이 충족될 때까지 실제 시간으로 폴링한다.
     *
     * `runTest` + `Dispatchers.Default` 조합에서도 안전하게 동작한다.
     * 조건 충족 시 즉시 반환하므로 고정 sleep보다 빠르고 안정적이다.
     *
     * @param timeoutMs 최대 대기 시간 (밀리초). 기본 2초.
     * @param intervalMs 폴링 간격 (밀리초). 기본 20ms.
     * @param description 타임아웃 시 표시할 설명 (디버깅 용도)
     * @param condition 충족 여부를 판별하는 조건 함수
     * @throws AssertionError 타임아웃 초과 시
     */
    fun pollUntil(
        timeoutMs: Long = 2000,
        intervalMs: Long = 20,
        description: String = "condition",
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "pollUntil timed out after ${timeoutMs}ms waiting for: $description"
                )
            }
            Thread.sleep(intervalMs)
        }
    }

    /**
     * [AtomicInteger]가 기대값 이상에 도달할 때까지 폴링한다.
     *
     * @param counter 관찰 대상 카운터
     * @param expected 기대하는 최소값
     * @param timeoutMs 최대 대기 시간 (밀리초)
     */
    fun pollCount(
        counter: AtomicInteger,
        expected: Int,
        timeoutMs: Long = 2000
    ) {
        pollUntil(
            timeoutMs = timeoutMs,
            description = "counter >= $expected (current: ${counter.get()})"
        ) {
            counter.get() >= expected
        }
    }

    /**
     * 배경 코루틴(Dispatchers.Default)의 정착을 대기한다.
     *
     * 네거티브 검증("이벤트가 처리되지 않아야 한다") 전에 호출하여
     * 배경 작업에 충분한 실행 시간을 부여한다.
     *
     * @param ms 대기 시간 (밀리초). 기본 500ms.
     */
    fun settleBackground(ms: Long = 500) {
        Thread.sleep(ms)
    }
}
