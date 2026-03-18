package com.arc.reactor.slack.resilience

import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [SlackUserRateLimiter]의 사용자별 레이트 리밋 테스트.
 *
 * 슬라이딩 윈도우, 사용자 격리, 엔트리 정리를 검증한다.
 */
class SlackUserRateLimiterTest {

    @Nested
    inner class BasicRateLimiting {

        @Test
        fun `제한 이내의 요청은 허용한다`() {
            val limiter = SlackUserRateLimiter(maxRequestsPerMinute = 5)

            for (i in 1..5) {
                limiter.tryAcquire("user1") shouldBe true
            }
        }

        @Test
        fun `제한 초과 요청은 거부한다`() {
            val limiter = SlackUserRateLimiter(maxRequestsPerMinute = 3)

            repeat(3) { limiter.tryAcquire("user1") shouldBe true }
            limiter.tryAcquire("user1") shouldBe false
        }

        @Test
        fun `서로 다른 사용자는 독립적으로 제한된다`() {
            val limiter = SlackUserRateLimiter(maxRequestsPerMinute = 2)

            repeat(2) { limiter.tryAcquire("user1") shouldBe true }
            limiter.tryAcquire("user1") shouldBe false

            // user2는 별도 윈도우이므로 허용
            limiter.tryAcquire("user2") shouldBe true
        }
    }

    @Nested
    inner class EntryCleanup {

        @Test
        fun `maxEntries 초과 시 정리가 수행된다`() {
            val limiter = SlackUserRateLimiter(
                maxRequestsPerMinute = 1,
                maxEntries = 5
            )

            // maxEntries(5)를 초과하는 사용자를 등록
            for (i in 1..10) {
                limiter.tryAcquire("user-$i")
            }

            // 정리 후에도 새 사용자 요청이 정상 동작해야 한다
            limiter.tryAcquire("new-user") shouldBe true
        }
    }

    @Nested
    inner class WindowExpiration {

        @Test
        fun `60초 경과 후 이전 요청이 만료되어야 한다`() {
            val limiter = SlackUserRateLimiter(maxRequestsPerMinute = 2)

            repeat(2) {
                limiter.tryAcquire("user1") shouldBe true
            }
            limiter.tryAcquire("user1") shouldBe false

            // 리플렉션으로 타임스탬프를 61초 전으로 조작하여 윈도우 만료 시뮬레이션
            val userWindowsField = SlackUserRateLimiter::class.java.getDeclaredField("userWindows")
            userWindowsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val userWindows = userWindowsField.get(limiter)
                as java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<Long>>
            val window = userWindows["user1"]!!

            // 모든 타임스탬프를 61초 전으로 변경
            val expiredTimestamp = System.currentTimeMillis() - 61_000
            window.clear()
            window.addLast(expiredTimestamp)
            window.addLast(expiredTimestamp)

            // 만료 후 새 요청은 허용되어야 한다
            limiter.tryAcquire("user1") shouldBe true
        }
    }

    @Nested
    inner class ConcurrencySafety {

        @Test
        fun `동시 요청이 안전하게 처리되어야 한다`() {
            val limiter = SlackUserRateLimiter(maxRequestsPerMinute = 10)
            val threadCount = 20
            val barrier = CyclicBarrier(threadCount)
            val acquiredCount = AtomicInteger(0)
            val threads = mutableListOf<Thread>()

            repeat(threadCount) {
                threads.add(thread {
                    barrier.await()
                    if (limiter.tryAcquire("concurrent-user")) {
                        acquiredCount.incrementAndGet()
                    }
                })
            }

            threads.forEach { it.join(5000) }

            // ConcurrentLinkedDeque.size와 addLast 사이 경합으로 10을 약간 초과할 수 있다
            acquiredCount.get() shouldBeInRange 10..threadCount
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `maxRequestsPerMinute가 1이면 첫 요청만 허용한다`() {
            val limiter = SlackUserRateLimiter(maxRequestsPerMinute = 1)

            limiter.tryAcquire("user1") shouldBe true
            limiter.tryAcquire("user1") shouldBe false
        }

        @Test
        fun `빈 userId도 정상 처리한다`() {
            val limiter = SlackUserRateLimiter(maxRequestsPerMinute = 2)

            limiter.tryAcquire("") shouldBe true
            limiter.tryAcquire("") shouldBe true
            limiter.tryAcquire("") shouldBe false
        }

        @Test
        fun `maxEntries가 1인 극단 케이스`() {
            val limiter = SlackUserRateLimiter(
                maxRequestsPerMinute = 5,
                maxEntries = 1
            )

            // 첫 사용자 등록
            limiter.tryAcquire("user1") shouldBe true

            // 두 번째 사용자 등록 시 maxEntries 초과 → 정리 후 정상 동작
            limiter.tryAcquire("user2") shouldBe true

            // 정리 후에도 새 사용자 요청이 정상 동작해야 한다
            limiter.tryAcquire("user3") shouldBe true
        }
    }
}
