package com.arc.reactor.slack.controller

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Slack Events API 콜백용 인메모리 중복 제거기.
 *
 * Slack은 이벤트 콜백마다 글로벌 고유 `event_id`를 전송한다.
 * 최근 처리한 ID를 보관하여 재시도 시 중복 처리를 방지한다.
 * TTL 만료 및 최대 엔트리 수 초과 시 자동으로 오래된 항목을 제거한다.
 *
 * [ConcurrentHashMap] 기반으로 글로벌 락 없이 동시 접근을 처리한다.
 * cleanup은 throttle 주기(5초)마다 최대 1회만 실행하여 핫 패스 부하를 최소화한다.
 *
 * @param enabled 활성화 여부 (false이면 항상 중복 아님으로 판정)
 * @param ttlSeconds 이벤트 ID 보관 기간 (초)
 * @param maxEntries 최대 보관 엔트리 수
 */
class SlackEventDeduplicator(
    private val enabled: Boolean = true,
    ttlSeconds: Long = 600,
    private val maxEntries: Int = 10_000,
    cleanupIntervalSeconds: Long = 5
) {
    private val ttlMillis = ttlSeconds.coerceAtLeast(1) * 1000
    private val seenEventIds = ConcurrentHashMap<String, Long>(128)

    /** cleanup 스로틀: 기본 5초 간격, 테스트에서 조정 가능 */
    private val lastCleanupMs = AtomicLong(0)
    private val cleanupIntervalMs = cleanupIntervalSeconds.coerceAtLeast(0) * 1000

    fun isDuplicateAndMark(eventId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!enabled || eventId.isBlank()) return false

        throttledCleanup(nowMillis)

        val existing = seenEventIds.putIfAbsent(eventId, nowMillis)
        return existing != null
    }

    fun size(): Int = seenEventIds.size

    /** 스로틀된 cleanup — 5초마다 최대 1회 실행. */
    private fun throttledCleanup(nowMillis: Long) {
        val lastMs = lastCleanupMs.get()
        if (nowMillis - lastMs < cleanupIntervalMs) return
        if (!lastCleanupMs.compareAndSet(lastMs, nowMillis)) return

        // TTL 만료 제거
        seenEventIds.entries.removeIf { (_, seenAt) -> nowMillis - seenAt > ttlMillis }

        // 오버플로우 제거 — 가장 오래된 것부터
        if (seenEventIds.size > maxEntries) {
            val overflow = seenEventIds.size - maxEntries
            seenEventIds.entries
                .sortedBy { it.value }
                .take(overflow)
                .forEach { seenEventIds.remove(it.key) }
        }
    }
}
