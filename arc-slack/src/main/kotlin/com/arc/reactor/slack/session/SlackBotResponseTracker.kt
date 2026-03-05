package com.arc.reactor.slack.session

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks bot response messages so that emoji reactions can be correlated back
 * to the original conversation session for feedback collection.
 *
 * Entries are automatically evicted after [ttlSeconds].
 */
class SlackBotResponseTracker(
    private val ttlSeconds: Long = 86400,
    private val maxEntries: Int = 50000
) {
    private val entries = ConcurrentHashMap<String, TrackedBotResponse>()

    fun track(channelId: String, messageTs: String, sessionId: String, userPrompt: String) {
        if (channelId.isBlank() || messageTs.isBlank()) return
        val now = Instant.now().epochSecond
        cleanup(now)
        entries[key(channelId, messageTs)] = TrackedBotResponse(
            sessionId = sessionId,
            userPrompt = userPrompt,
            expiresAt = now + ttlSeconds.coerceAtLeast(1)
        )
    }

    fun lookup(channelId: String, messageTs: String): TrackedBotResponse? {
        if (channelId.isBlank() || messageTs.isBlank()) return null
        val now = Instant.now().epochSecond
        val mapKey = key(channelId, messageTs)
        val entry = entries[mapKey] ?: return null
        if (entry.expiresAt <= now) {
            entries.remove(mapKey)
            return null
        }
        return entry
    }

    private fun cleanup(nowEpochSeconds: Long) {
        entries.entries.removeIf { it.value.expiresAt <= nowEpochSeconds }
        if (entries.size <= maxEntries) return

        val overflow = entries.size - maxEntries
        if (overflow <= 0) return
        entries.entries
            .asSequence()
            .sortedBy { it.value.expiresAt }
            .take(overflow)
            .forEach { entries.remove(it.key) }
    }

    private fun key(channelId: String, messageTs: String): String = "$channelId:$messageTs"
}

data class TrackedBotResponse(
    val sessionId: String,
    val userPrompt: String,
    val expiresAt: Long
)
