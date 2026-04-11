package com.arc.reactor.slack.session

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * SlackThreadTracker에 대한 테스트.
 *
 * TTL 만료, maxEntries 퇴거, 빈 입력 방어, 동시 접근 안전성을 검증한다.
 */
class SlackThreadTrackerTest {

    @Nested
    inner class TrackAndIsTracked {

        @Test
        fun `추적된 스레드는 true를 반환한다`() {
            val tracker = SlackThreadTracker()
            tracker.track("C1", "1000.001")

            tracker.isTracked("C1", "1000.001") shouldBe true
        }

        @Test
        fun `추적되지 않은 스레드는 false를 반환한다`() {
            val tracker = SlackThreadTracker()

            tracker.isTracked("C1", "9999.999") shouldBe false
        }

        @Test
        fun `다른 채널의 같은 threadTs는 별도 추적한다`() {
            val tracker = SlackThreadTracker()
            tracker.track("C1", "1000.001")

            tracker.isTracked("C2", "1000.001") shouldBe false
        }

        @Test
        fun `같은 채널의 다른 threadTs는 별도 추적한다`() {
            val tracker = SlackThreadTracker()
            tracker.track("C1", "1000.001")

            tracker.isTracked("C1", "2000.002") shouldBe false
        }
    }

    @Nested
    inner class BlankInputGuard {

        @Test
        fun `빈 channelId track은 무시한다`() {
            val tracker = SlackThreadTracker()
            tracker.track("", "1000.001")

            tracker.isTracked("", "1000.001") shouldBe false
        }

        @Test
        fun `빈 threadTs track은 무시한다`() {
            val tracker = SlackThreadTracker()
            tracker.track("C1", "")

            tracker.isTracked("C1", "") shouldBe false
        }

        @Test
        fun `공백 channelId isTracked는 false를 반환한다`() {
            val tracker = SlackThreadTracker()
            tracker.track("C1", "1000.001")

            tracker.isTracked("  ", "1000.001") shouldBe false
        }
    }

    @Nested
    inner class TtlExpiry {

        @Test
        fun `TTL이 최소 60초로 강제되므로 짧은 TTL에서도 즉시 만료되지 않는다`() {
            // ttlSeconds=1이어도 coerceAtLeast(60)으로 최소 60초
            val tracker = SlackThreadTracker(ttlSeconds = 1)
            tracker.track("C1", "1000.001")

            // 즉시 확인 — 최소 60초 TTL이므로 유효해야 한다
            tracker.isTracked("C1", "1000.001") shouldBe true

            // 2초 후에도 여전히 유효 (60초 최소 보장)
            Thread.sleep(2000)
            tracker.isTracked("C1", "1000.001") shouldBe true
        }

        @Test
        fun `기본 TTL(24시간)로 추적된 스레드는 즉시 유효하다`() {
            val tracker = SlackThreadTracker()
            tracker.track("C1", "1000.001")

            tracker.isTracked("C1", "1000.001") shouldBe true
        }
    }

    @Nested
    inner class MaxEntriesEviction {

        @Test
        fun `maxEntries 초과 시 퇴거하여 크기를 제한한다`() {
            val tracker = SlackThreadTracker(maxEntries = 3)

            // 5개 추적
            tracker.track("C1", "1.0")
            Thread.sleep(2)
            tracker.track("C2", "2.0")
            Thread.sleep(2)
            tracker.track("C3", "3.0")
            Thread.sleep(2)
            tracker.track("C4", "4.0")
            Thread.sleep(2)
            tracker.track("C5", "5.0")

            // R292: Caffeine cleanUp() 강제 → LRU eviction
            tracker.trackedThreads.cleanUp()

            // 가장 최근 항목은 반드시 살아있어야 한다
            tracker.isTracked("C5", "5.0") shouldBe true

            // R292: Caffeine maximumSize 보장 — 생존 수는 maxEntries(=3) 이하여야 한다
            val surviving = (1..5).count { i -> tracker.isTracked("C$i", "$i.0") }
            (surviving <= 3) shouldBe true
        }

        @Test
        fun `maxEntries 이하면 퇴거하지 않는다`() {
            val tracker = SlackThreadTracker(maxEntries = 5)

            repeat(5) { i -> tracker.track("C$i", "$i.0") }

            // 모두 살아있어야 한다
            repeat(5) { i ->
                tracker.isTracked("C$i", "$i.0") shouldBe true
            }
        }
    }

    @Nested
    inner class ConcurrentAccess {

        @Test
        fun `여러 스레드에서 동시에 track해도 안전하다`() {
            val tracker = SlackThreadTracker(maxEntries = 1000)

            val threads = (1..10).map { i ->
                Thread {
                    repeat(50) { j ->
                        tracker.track("C$i", "$j.0")
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            // 최소한 최근 추가된 항목들은 추적되어야 한다
            tracker.isTracked("C10", "49.0") shouldBe true
        }
    }
}
