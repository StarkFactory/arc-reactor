package com.arc.reactor.slack.session

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks Slack thread IDs initiated by Arc Reactor so follow-up message events
 * can be scoped to known conversations only.
 */
class SlackThreadTracker(
    private val ttlSeconds: Long = 86400,
    private val maxEntries: Int = 20000
) {
    private val trackedThreads = ConcurrentHashMap<String, Long>()

    fun track(channelId: String, threadTs: String) {
        if (channelId.isBlank() || threadTs.isBlank()) return
        val now = Instant.now().epochSecond
        cleanup(now)
        trackedThreads[key(channelId, threadTs)] = now + ttlSeconds.coerceAtLeast(60)
    }

    fun isTracked(channelId: String, threadTs: String): Boolean {
        if (channelId.isBlank() || threadTs.isBlank()) return false
        val now = Instant.now().epochSecond
        val mapKey = key(channelId, threadTs)
        val expiresAt = trackedThreads[mapKey] ?: return false
        if (expiresAt <= now) {
            trackedThreads.remove(mapKey, expiresAt)
            return false
        }
        return true
    }

    private fun cleanup(nowEpochSeconds: Long) {
        trackedThreads.entries.removeIf { it.value <= nowEpochSeconds }
        if (trackedThreads.size <= maxEntries) return

        val overflow = trackedThreads.size - maxEntries
        if (overflow <= 0) return
        trackedThreads.entries
            .asSequence()
            .sortedBy { it.value }
            .take(overflow)
            .forEach { trackedThreads.remove(it.key, it.value) }
    }

    private fun key(channelId: String, threadTs: String): String = "$channelId:$threadTs"
}

