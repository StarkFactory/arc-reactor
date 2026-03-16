package com.arc.reactor.slack.controller

import java.util.LinkedHashMap

/**
 * Slack Events API 콜백용 인메모리 중복 제거기.
 *
 * Slack은 이벤트 콜백마다 글로벌 고유 `event_id`를 전송한다.
 * 최근 처리한 ID를 보관하여 재시도 시 중복 처리를 방지한다.
 * TTL 만료 및 최대 엔트리 수 초과 시 자동으로 오래된 항목을 제거한다.
 *
 * @param enabled 활성화 여부 (false이면 항상 중복 아님으로 판정)
 * @param ttlSeconds 이벤트 ID 보관 기간 (초)
 * @param maxEntries 최대 보관 엔트리 수
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
