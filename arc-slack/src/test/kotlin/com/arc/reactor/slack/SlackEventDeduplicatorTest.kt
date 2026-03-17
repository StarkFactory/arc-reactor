package com.arc.reactor.slack

import com.arc.reactor.slack.controller.SlackEventDeduplicator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [SlackEventDeduplicator]의 이벤트 중복 제거 테스트.
 *
 * 첫 번째 이벤트는 신규로 표시, 두 번째는 중복으로 표시,
 * TTL 만료 후 재처리 허용 등을 검증한다.
 */
class SlackEventDeduplicatorTest {

    @Test
    fun `marks은(는) first event as new and second as duplicate`() {
        val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 60, maxEntries = 100)

        deduplicator.isDuplicateAndMark("Ev123") shouldBe false
        deduplicator.isDuplicateAndMark("Ev123") shouldBe true
    }

    @Test
    fun `ttl후 expires old entries`() {
        val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 1, maxEntries = 100)

        deduplicator.isDuplicateAndMark("Ev123", nowMillis = 1_000) shouldBe false
        deduplicator.isDuplicateAndMark("Ev123", nowMillis = 1_500) shouldBe true
        deduplicator.isDuplicateAndMark("Ev123", nowMillis = 2_500) shouldBe false
    }
}
