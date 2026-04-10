package com.arc.reactor.slack.session

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Arc Reactor가 개시한 Slack 스레드 ID를 추적하여,
 * 후속 메시지 이벤트를 알려진 대화로만 제한하는 추적기.
 *
 * 추적되지 않은 스레드의 메시지는 [SlackEventProcessor]에서 무시된다.
 *
 * @param ttlSeconds 스레드 추적 보관 기간 (초, 기본 24시간)
 * @param maxEntries 최대 추적 엔트리 수
 * @see SlackEventProcessor
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

    /**
     * 특정 스레드를 추적에서 제거한다 — "나가" 명령 등으로 사용자가 봇을 명시적으로 내보낼 때 사용.
     *
     * 제거 후 해당 스레드에 새 메시지가 와도 봇은 응답하지 않는다 (추적 안 됨 → 무시).
     * 새로운 멘션(`@reactor`)이 오면 자동으로 다시 추적된다.
     */
    fun untrack(channelId: String, threadTs: String) {
        if (channelId.isBlank() || threadTs.isBlank()) return
        trackedThreads.remove(key(channelId, threadTs))
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
        val victims = trackedThreads.entries
            .asSequence()
            .sortedBy { it.value }
            .take(overflow)
            .toList()
        for (victim in victims) {
            trackedThreads.remove(victim.key, victim.value)
        }
    }

    private fun key(channelId: String, threadTs: String): String = "$channelId:$threadTs"
}

