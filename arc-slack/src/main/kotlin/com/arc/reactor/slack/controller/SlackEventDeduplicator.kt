package com.arc.reactor.slack.controller

import java.util.LinkedHashMap

/**
 * In-memory deduplicator for Slack Events API callbacks.
 *
 * Slack sends a globally unique `event_id` for event callbacks. We keep
 * recently processed IDs to prevent duplicate handling on retries.
 */
class SlackEventDeduplicator(
    private val enabled: Boolean = true,
    ttlSeconds: Long = 600,
    private val maxEntries: Int = 10_000
) {
    private val ttlMillis = ttlSeconds.coerceAtLeast(1) * 1000
    private val seenEventIds = LinkedHashMap<String, Long>(128, 0.75f, true)

    @Synchronized
    fun isDuplicateAndMark(eventId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!enabled || eventId.isBlank()) return false

        evictExpired(nowMillis)

        if (seenEventIds.containsKey(eventId)) {
            return true
        }

        seenEventIds[eventId] = nowMillis
        evictOverflow()
        return false
    }

    @Synchronized
    fun size(): Int = seenEventIds.size

    private fun evictExpired(nowMillis: Long) {
        val iterator = seenEventIds.entries.iterator()
        while (iterator.hasNext()) {
            val (_, seenAt) = iterator.next()
            if (nowMillis - seenAt > ttlMillis) {
                iterator.remove()
            }
        }
    }

    private fun evictOverflow() {
        while (seenEventIds.size > maxEntries) {
            val iterator = seenEventIds.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }
}
