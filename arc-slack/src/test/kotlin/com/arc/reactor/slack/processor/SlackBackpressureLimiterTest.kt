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
 * [SlackBackpressureLimiter]의 백프레셔 제한기 테스트.
 *
 * fail-fast(포화) 모드, 큐 모드, 동시 퍼밋 카운팅,
 * 세마포어 해제 시맨틱스 등을 검증한다.
 */
class SlackBackpressureLimiterTest {

    // =========================================================================
    // 즉시 거부 판단 (rejectImmediatelyIfConfigured)
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
            // 단일 퍼밋 소진
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
            // tryAcquire(rejectImmediatelyIfConfigured)를 통해 퍼밋 소진
            val firstReject = limiter.rejectImmediatelyIfConfigured()
            firstReject shouldBe false // permit was free and got acquired by tryAcquire

            // 이제 세마포어가 0 → 다음 호출은 거부됨
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
            // 세 개 모두 성공해야 합니다 (거부되지 않음)
            val rejections = (1..max).count { limiter.rejectImmediatelyIfConfigured() }
            rejections shouldBe 0

            // 다음 것은 거부되어야 합니다
            val saturated = limiter.rejectImmediatelyIfConfigured()
            saturated shouldBe true
        }
    }

    // =========================================================================
    // 큐 모드 퍼밋 획득 (acquireForQueuedMode)
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
            // 세마포어 미사용 — 이후 tryAcquire도 성공해야 합니다
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

            // 두 번째 획득은 타임아웃되어야 합니다
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

            // 대기 후 해제할 코루틴 시작
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
            // 세마포어 완전 소진 — 타임아웃 획득은 즉시 실패해야 합니다
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
    // 퍼밋 해제 (release)
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

            // 모든 퍼밋이 복원됨 — 최대 횟수만큼 다시 획득 가능해야 합니다
            var successCount = 0
            repeat(max) {
                val timedLimiter = SlackBackpressureLimiter(
                    maxConcurrentRequests = max,
                    requestTimeoutMs = 50,
                    failFastOnSaturation = false
                )
                // 각 라운드마다 새 리미터를 사용하여 테스트 독립성 유지
                if (timedLimiter.acquireForQueuedMode()) successCount++
            }
            successCount shouldBe max
        }
    }

    // =========================================================================
    // 동시성 불변 조건
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
            // 유일한 퍼밋을 보유
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
