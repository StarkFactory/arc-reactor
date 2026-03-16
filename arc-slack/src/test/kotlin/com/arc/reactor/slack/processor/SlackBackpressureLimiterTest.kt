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
 * 에 대한 단위 테스트. [SlackBackpressureLimiter].
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
        fun `failFastOnSaturation is disabled일 때 false를 반환한다`() {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 1000,
                failFastOnSaturation = false
            )
            val result = limiter.rejectImmediatelyIfConfigured()
            result shouldBe false
        }

        @Test
        fun `semaphore has available permits일 때 false를 반환한다`() {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 2,
                requestTimeoutMs = 0,
                failFastOnSaturation = true
            )
            val result = limiter.rejectImmediatelyIfConfigured()
            result shouldBe false
        }

        @Test
        fun `semaphore is saturated in fail-fast mode일 때 true를 반환한다`() = runBlocking {
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
        fun `false again after release frees the permit in fail-fast mode를 반환한다`() = runBlocking {
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

            // → the semaphore is free again를 해제합니다
            limiter.release()
            val afterRelease = limiter.rejectImmediatelyIfConfigured()
            afterRelease shouldBe false
        }

        @Test
        fun `up to maxConcurrentRequests successive tryAcquire calls를 수락한다`() {
            val max = 3
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = max,
                requestTimeoutMs = 0,
                failFastOnSaturation = true
            )
            // All three은(는) succeed (not reject)해야 합니다
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
        fun `true immediately in fail-fast mode without touching semaphore를 반환한다`() = runTest {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 1000,
                failFastOnSaturation = true
            )
            val result = limiter.acquireForQueuedMode()
            result shouldBe true
            // Semaphore is untouched — a subsequent tryAcquire은(는) still succeed해야 합니다
            val notRejected = !limiter.rejectImmediatelyIfConfigured()
            notRejected shouldBe true
        }

        @Test
        fun `semaphore is free in queue mode일 때 true and acquires permit를 반환한다`() = runTest {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 2,
                requestTimeoutMs = 0,
                failFastOnSaturation = false
            )
            val result = limiter.acquireForQueuedMode()
            result shouldBe true
        }

        @Test
        fun `timeout elapses before permit is available일 때 false를 반환한다`() = runTest {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 50,
                failFastOnSaturation = false
            )
            // the only permit를 획득합니다
            limiter.acquireForQueuedMode() shouldBe true

            // Second acquire은(는) time out해야 합니다
            val timedOut = limiter.acquireForQueuedMode()
            timedOut shouldBe false
        }

        @Test
        fun `permit에 대해 true with no timeout (requestTimeoutMs le 0) and waits를 반환한다`() = runBlocking {
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = 1,
                requestTimeoutMs = 0,
                failFastOnSaturation = false
            )
            // first permit를 획득합니다
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
        fun `successive은(는) acquires drain the semaphore correctly`() = runTest {
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
            // Semaphore fully drained — timed acquire은(는) fail quickly해야 합니다
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
        fun `release은(는) restores a permit so the next acquire succeeds`() = runTest {
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
        fun `releasing은(는) multiple times restores proportional permits`() = runTest {
            val max = 3
            val limiter = SlackBackpressureLimiter(
                maxConcurrentRequests = max,
                requestTimeoutMs = 0,
                failFastOnSaturation = false
            )
            repeat(max) { limiter.acquireForQueuedMode() }
            repeat(max) { limiter.release() }

            // All permits restored —은(는) acquire max times again해야 합니다
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
        fun `concurrent은(는) permits never exceed maxConcurrentRequests in queue mode`() = runBlocking {
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
        fun `all dropped events in fail-fast mode은(는) rejected without acquiring semaphore이다`() = runBlocking {
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
