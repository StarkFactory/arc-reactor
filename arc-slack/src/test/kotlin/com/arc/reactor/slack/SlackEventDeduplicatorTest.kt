package com.arc.reactor.slack

import com.arc.reactor.slack.controller.SlackEventDeduplicator
import com.github.benmanes.caffeine.cache.Ticker
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [SlackEventDeduplicator]의 이벤트 중복 제거 테스트.
 *
 * 첫 번째 이벤트는 신규로 표시, 두 번째는 중복으로 표시,
 * TTL 만료 후 재처리 허용 등을 검증한다.
 *
 * R292: ConcurrentHashMap → Caffeine 마이그레이션 후 [FakeTicker]로 시간 제어.
 */
class SlackEventDeduplicatorTest {

    @Test
    fun `marks은(는) first event as new and second as duplicate`() {
        val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 60, maxEntries = 100, cleanupIntervalSeconds = 0)

        deduplicator.isDuplicateAndMark("Ev123") shouldBe false
        deduplicator.isDuplicateAndMark("Ev123") shouldBe true
    }

    @Test
    fun `ttl후 expires old entries`() {
        // R292: Caffeine ticker로 시간 제어
        val ticker = FakeTicker()
        val deduplicator = SlackEventDeduplicator(
            enabled = true, ttlSeconds = 1, maxEntries = 100, cleanupIntervalSeconds = 0, ticker = ticker
        )

        deduplicator.isDuplicateAndMark("Ev123") shouldBe false
        ticker.advanceMillis(500)
        deduplicator.isDuplicateAndMark("Ev123") shouldBe true
        ticker.advanceMillis(1000) // 총 1500ms 경과 → TTL 1초 만료
        deduplicator.isDuplicateAndMark("Ev123") shouldBe false
    }
}

/**
 * R292: Caffeine 시간 source mock — 테스트에서 시간을 임의로 advance할 수 있다.
 *
 * Caffeine의 `Ticker.read()`는 nanoseconds 반환을 요구한다.
 */
class FakeTicker : Ticker {
    @Volatile
    private var nanos: Long = 0

    override fun read(): Long = nanos

    fun advanceMillis(millis: Long) {
        nanos += millis * 1_000_000
    }

    fun advanceSeconds(seconds: Long) {
        nanos += seconds * 1_000_000_000
    }
}
