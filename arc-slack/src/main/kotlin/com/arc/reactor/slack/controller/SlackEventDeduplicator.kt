package com.arc.reactor.slack.controller

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import java.time.Duration

/**
 * Slack Events API 콜백용 인메모리 중복 제거기.
 *
 * Slack은 이벤트 콜백마다 글로벌 고유 `event_id`를 전송한다.
 * 최근 처리한 ID를 보관하여 재시도 시 중복 처리를 방지한다.
 * TTL 만료 및 최대 엔트리 수 초과 시 Caffeine이 자동으로 오래된 항목을 제거한다.
 *
 * R292 fix: ConcurrentHashMap + 수동 throttledCleanup → Caffeine bounded cache
 * (CLAUDE.md 준수). 이전 구현은 다음 두 문제가 있었다:
 *
 * 1. **CLAUDE.md 위반**: ConcurrentHashMap 사용 금지
 * 2. **Dedup TOCTOU race**: `throttledCleanup`이 `entries.removeIf`로 만료 항목을
 *    삭제하는 동안 다른 스레드의 `putIfAbsent`가 같은 시점에 진행될 수 있어,
 *    cleanup이 막 삭제한 entryId를 다른 스레드가 새로 insert → 두 스레드 모두 `null`
 *    반환받아 `false (not duplicate)` 판정 → **같은 eventId가 두 번 처리될 수 있음**
 *
 * R292 fix: Caffeine의 `expireAfterWrite + maximumSize` 자동 eviction을 사용하고,
 * `asMap().putIfAbsent` atomic 연산으로 dedup race를 자동 해결. 수동 cleanup 로직
 * 제거로 코드 단순화 및 race window 차단.
 *
 * @param enabled 활성화 여부 (false이면 항상 중복 아님으로 판정)
 * @param ttlSeconds 이벤트 ID 보관 기간 (초)
 * @param maxEntries 최대 보관 엔트리 수
 * @param cleanupIntervalSeconds (deprecated, R292 후 무시) — 시그니처 backward compat
 * @param ticker Caffeine 시간 source — 테스트에서 [com.github.benmanes.caffeine.cache.Ticker] 주입 가능
 */
class SlackEventDeduplicator(
    private val enabled: Boolean = true,
    ttlSeconds: Long = 600,
    maxEntries: Int = 10_000,
    @Suppress("UNUSED_PARAMETER") cleanupIntervalSeconds: Long = 5,
    ticker: Ticker = Ticker.systemTicker()
) {
    /**
     * R292: Caffeine bounded cache. expireAfterWrite로 TTL 자동 만료, maximumSize로
     * LRU eviction 자동 처리. asMap().putIfAbsent로 dedup atomic 보장.
     *
     * `internal`로 노출하여 테스트가 [Cache.cleanUp]을 강제 호출하여 lazy eviction을
     * 동기화할 수 있게 한다.
     */
    internal val seenEventIds: Cache<String, Long> = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(Duration.ofSeconds(ttlSeconds.coerceAtLeast(1)))
        .maximumSize(maxEntries.coerceAtLeast(1).toLong())
        .build()

    fun isDuplicateAndMark(eventId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!enabled || eventId.isBlank()) return false
        // R292: asMap().putIfAbsent는 atomic. Caffeine은 expired entry를 lazy하게
        // null로 반환하므로 만료된 ID는 다시 새로 insert된다.
        val existing = seenEventIds.asMap().putIfAbsent(eventId, nowMillis)
        return existing != null
    }

    fun size(): Int {
        seenEventIds.cleanUp()
        return seenEventIds.estimatedSize().toInt()
    }
}
