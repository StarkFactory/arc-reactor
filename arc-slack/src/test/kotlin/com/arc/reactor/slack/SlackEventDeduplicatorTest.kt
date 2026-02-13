package com.arc.reactor.slack

import com.arc.reactor.slack.controller.SlackEventDeduplicator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SlackEventDeduplicatorTest {

    @Test
    fun `marks first event as new and second as duplicate`() {
        val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 60, maxEntries = 100)

        deduplicator.isDuplicateAndMark("Ev123") shouldBe false
        deduplicator.isDuplicateAndMark("Ev123") shouldBe true
    }

    @Test
    fun `expires old entries after ttl`() {
        val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 1, maxEntries = 100)

        deduplicator.isDuplicateAndMark("Ev123", nowMillis = 1_000) shouldBe false
        deduplicator.isDuplicateAndMark("Ev123", nowMillis = 1_500) shouldBe true
        deduplicator.isDuplicateAndMark("Ev123", nowMillis = 2_500) shouldBe false
    }
}
