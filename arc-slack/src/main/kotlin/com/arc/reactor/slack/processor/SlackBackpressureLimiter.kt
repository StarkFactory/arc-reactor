package com.arc.reactor.slack.processor

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Slack 이벤트/명령 처리의 backpressure 제어기.
 *
 * 코루틴 [Semaphore]를 사용하여 동시 처리 수를 제한한다.
 * 두 가지 모드를 지원한다:
 * - Fail-fast: 세마포어가 포화되면 즉시 거부
 * - Queue: 지정 시간까지 대기 후 타임아웃 시 거부
 *
 * @param maxConcurrentRequests 최대 동시 처리 수
 * @param requestTimeoutMs 큐 모드에서 세마포어 대기 타임아웃 (밀리초)
 * @param failFastOnSaturation true이면 fail-fast 모드, false이면 큐 모드
 */
internal class SlackBackpressureLimiter(
    maxConcurrentRequests: Int,
    private val requestTimeoutMs: Long,
    private val failFastOnSaturation: Boolean
) {

    private val semaphore = Semaphore(maxConcurrentRequests)

    /**
     * Fail-fast 모드 전용: 즉시 세마포어 획득을 시도한다.
     * 획득 실패(요청 거부 필요) 시 true를 반환한다.
     */
    fun rejectImmediatelyIfConfigured(): Boolean {
        if (!failFastOnSaturation) return false
        return !semaphore.tryAcquire()
    }

    /**
     * 큐 모드 전용: 타임아웃까지 세마포어 획득을 대기한다.
     * 획득 성공 시 true, 타임아웃 시 false를 반환한다.
     */
    suspend fun acquireForQueuedMode(): Boolean {
        if (failFastOnSaturation) return true
        if (requestTimeoutMs <= 0) {
            semaphore.acquire()
            return true
        }
        return withTimeoutOrNull(requestTimeoutMs) {
            semaphore.acquire()
            true
        } ?: false
    }

    fun release() {
        semaphore.release()
    }
}
