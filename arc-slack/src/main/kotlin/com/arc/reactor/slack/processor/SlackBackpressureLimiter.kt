package com.arc.reactor.slack.processor

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

internal class SlackBackpressureLimiter(
    maxConcurrentRequests: Int,
    private val requestTimeoutMs: Long,
    private val failFastOnSaturation: Boolean
) {

    private val semaphore = Semaphore(maxConcurrentRequests)

    /**
     * Fail-fast mode only: tries to acquire immediately.
     * Returns true when request should be rejected.
     */
    fun rejectImmediatelyIfConfigured(): Boolean {
        if (!failFastOnSaturation) return false
        return !semaphore.tryAcquire()
    }

    /**
     * Queue mode only: waits for permit with optional timeout.
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
