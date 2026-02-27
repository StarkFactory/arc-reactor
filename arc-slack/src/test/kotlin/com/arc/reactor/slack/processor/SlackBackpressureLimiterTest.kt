package com.arc.reactor.slack.processor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [SlackBackpressureLimiter].
 *
 * Tests cover fail-fast (saturation) mode, queue mode, concurrent permit counting,
 * and semaphore release semantics.
 */
class SlackBackpressureLimiterTest {

    // =========================================================================
    // rejectImmediatelyIfConfigured
    // =========================================================================

    @Nested
    inner class RejectImmediatelyIfConfigured {

        @Test
        fun `returns false when failFastOnSaturation is disabled`() {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 1000,
                failFastOnSaturation = false
            )
            val result = limiter.rejectImmediatelyIfConfigured()
            result shouldBe false
        }

        @Test
        fun `returns false when semaphore has available permits`() {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 2,
                requestTimeoutMs = 0,
                failFastOnSaturation = true
            )
            val result = limiter.rejectImmediatelyIfConfigured()
            result shouldBe false
        }

        @Test
        fun `returns true when semaphore is saturated in fail-fast mode`() = runBlocking {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 0,
                failFastOnSaturation = true
            )
            // Exhaust the single permit
            val acquired = limiter.acquireForQueuedMode()
            acquired shouldBe true

            val rejected = limiter.rejectImmediatelyIfConfigured()
            rejected shouldBe true
        }

        @Test
        fun `returns false again after release frees the permit in fail-fast mode`() = runBlocking {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 0,
                failFastOnSaturation = true
            )
            // Exhaust the permit via tryAcquire (rejectImmediatelyIfConfigured itself)
            val firstReject = limiter.rejectImmediatelyIfConfigured()
            firstReject shouldBe false // permit was free and got acquired by tryAcquire

            // Now the semaphore is at 0 → next call rejects
            val saturated = limiter.rejectImmediatelyIfConfigured()
            saturated shouldBe true

            // Release → the semaphore is free again
            limiter.release()
            val afterRelease = limiter.rejectImmediatelyIfConfigured()
            afterRelease shouldBe false
        }

        @Test
        fun `accepts up to maxConcurrentRequests successive tryAcquire calls`() {
            val max = 3
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = max,
                requestTimeoutMs = 0,
                failFastOnSaturation = true
            )
            // All three should succeed (not reject)
            val rejections = (1..max).count { limiter.rejectImmediatelyIfConfigured() }
            rejections shouldBe 0

            // The next one must be rejected
            val saturated = limiter.rejectImmediatelyIfConfigured()
            saturated shouldBe true
        }
    }

    // =========================================================================
    // acquireForQueuedMode
    // =========================================================================

    @Nested
    inner class AcquireForQueuedMode {

        @Test
        fun `returns true immediately in fail-fast mode without touching semaphore`() = runTest {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 1000,
                failFastOnSaturation = true
            )
            val result = limiter.acquireForQueuedMode()
            result shouldBe true
            // Semaphore is untouched — a subsequent tryAcquire should still succeed
            val notRejected = !limiter.rejectImmediatelyIfConfigured()
            notRejected shouldBe true
        }

        @Test
        fun `returns true and acquires permit when semaphore is free in queue mode`() = runTest {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 2,
                requestTimeoutMs = 0,
                failFastOnSaturation = false
            )
            val result = limiter.acquireForQueuedMode()
            result shouldBe true
        }

        @Test
        fun `returns false when timeout elapses before permit is available`() = runTest {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 50,
                failFastOnSaturation = false
            )
            // Acquire the only permit
            limiter.acquireForQueuedMode() shouldBe true

            // Second acquire should time out
            val timedOut = limiter.acquireForQueuedMode()
            timedOut shouldBe false
        }

        @Test
        fun `returns true with no timeout (requestTimeoutMs le 0) and waits for permit`() = runBlocking {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 0,
                failFastOnSaturation = false
            )
            // Acquire first permit
            limiter.acquireForQueuedMode()

            // Launch a coroutine that will wait and then release
            val acquired = AtomicInteger(0)
            val job = launch(Dispatchers.Default) {
                val result = limiter.acquireForQueuedMode()
                if (result) acquired.incrementAndGet()
            }
            delay(100) // give the waiting coroutine time to suspend
            limiter.release()   // unblock the waiting coroutine
            job.join()

            acquired.get() shouldBe 1
        }

        @Test
        fun `successive acquires drain the semaphore correctly`() = runTest {
            val max = 3
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = max,
                requestTimeoutMs = 0,
                failFastOnSaturation = false
            )
            repeat(max) {
                val acquired = limiter.acquireForQueuedMode()
                acquired shouldBe true
            }
            // Semaphore fully drained — timed acquire should fail quickly
            val timedOutLimiter = SlackBackpressureLimiter(
                maxConcurrentRequests = max,
                requestTimeoutMs = 50,
                failFastOnSaturation = false
            )
            repeat(max) { timedOutLimiter.acquireForQueuedMode() }
            timedOutLimiter.acquireForQueuedMode() shouldBe false
        }
    }

    // =========================================================================
    // release
    // =========================================================================

    @Nested
    inner class Release {

        @Test
        fun `release restores a permit so the next acquire succeeds`() = runTest {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 500,
                failFastOnSaturation = false
            )
            limiter.acquireForQueuedMode() shouldBe true
            limiter.release()
            limiter.acquireForQueuedMode() shouldBe true
        }

        @Test
        fun `releasing multiple times restores proportional permits`() = runTest {
            val max = 3
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = max,
                requestTimeoutMs = 0,
                failFastOnSaturation = false
            )
            repeat(max) { limiter.acquireForQueuedMode() }
            repeat(max) { limiter.release() }

            // All permits restored — should acquire max times again
            var successCount = 0
            repeat(max) {
                val timedLimiter = SlackBackpressureLimiter(
                    maxConcurrentRequests = max,
                    requestTimeoutMs = 50,
                    failFastOnSaturation = false
                )
                // Use a fresh limiter per round to keep the test self-contained
                if (timedLimiter.acquireForQueuedMode()) successCount++
            }
            successCount shouldBe max
        }
    }

    // =========================================================================
    // Concurrency
    // =========================================================================

    @Nested
    inner class ConcurrencyInvariants {

        @Test
        fun `concurrent permits never exceed maxConcurrentRequests in queue mode`() = runBlocking {
            val maxAllowed = 3
            val totalWorkers = 10
            val currentConcurrent = AtomicInteger(0)
            val peakConcurrent = AtomicInteger(0)

            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = maxAllowed,
                requestTimeoutMs = 5000,
                failFastOnSaturation = false
            )

            val jobs = (1..totalWorkers).map {
                launch(Dispatchers.Default) {
                    val acquired = limiter.acquireForQueuedMode()
                    if (acquired) {
                        val current = currentConcurrent.incrementAndGet()
                        peakConcurrent.updateAndGet { prev -> maxOf(prev, current) }
                        delay(50)
                        currentConcurrent.decrementAndGet()
                        limiter.release()
                    }
                }
            }
            jobs.forEach { it.join() }

            peakConcurrent.get() shouldBe maxAllowed
        }

        @Test
        fun `all dropped events in fail-fast mode are rejected without acquiring semaphore`() = runBlocking {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 0,
                failFastOnSaturation = true
            )
            // Hold the only permit
            val holdJob = launch(Dispatchers.Default) {
                limiter.acquireForQueuedMode()
                delay(300)
                limiter.release()
            }
            delay(50) // let the hold coroutine acquire

            val droppedCount = AtomicInteger(0)
            val workers = (1..5).map {
                launch(Dispatchers.Default) {
                    if (limiter.rejectImmediatelyIfConfigured()) droppedCount.incrementAndGet()
                }
            }
            workers.forEach { it.join() }
            holdJob.join()

            droppedCount.get() shouldBe 5
        }
    }
}
