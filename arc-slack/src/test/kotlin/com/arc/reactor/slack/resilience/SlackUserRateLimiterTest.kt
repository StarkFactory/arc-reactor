package com.arc.reactor.slack.resilience

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
    }
}
