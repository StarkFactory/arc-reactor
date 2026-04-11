package com.arc.reactor.slack.session

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import java.time.Duration

/**
 * Arc Reactor가 개시한 Slack 스레드 ID를 추적하여,
 * 후속 메시지 이벤트를 알려진 대화로만 제한하는 추적기.
 *
 * 추적되지 않은 스레드의 메시지는 [SlackEventProcessor]에서 무시된다.
 *
 * R292 fix: ConcurrentHashMap + 수동 cleanup → Caffeine bounded cache (CLAUDE.md 준수).
 * 이전 구현은 ConcurrentHashMap 사용 + `sortedBy { it.value }` 기반 eviction이
 * hot path에서 O(n log n)이며, snapshot 후 size > maxEntries 체크와 removeIf 사이에
 * 다른 thread의 track()가 끼어들면 동시 trim race로 카운터가 음수가 될 수 있었다.
 * Caffeine의 expireAfterWrite + maximumSize는 lazy/atomic eviction을 보장한다.
 *
 * @param ttlSeconds 스레드 추적 보관 기간 (초, 기본 24시간)
 * @param maxEntries 최대 추적 엔트리 수
 * @see SlackEventProcessor
 */
class SlackThreadTracker(
    private val ttlSeconds: Long = 86400,
    maxEntries: Int = 20000,
    ticker: Ticker = Ticker.systemTicker()
) {
    /**
     * R292: Caffeine bounded cache. expireAfterWrite로 TTL 자동 만료, maximumSize로
     * LRU eviction 자동 처리. value는 expiration epoch seconds (디버깅용) — Caffeine
     * 자체 expiration이 정답이지만 기존 코드 구조 유지를 위해 보존.
     *
     * `internal`로 노출하여 테스트가 [Cache.cleanUp]을 강제 호출할 수 있게 한다.
     */
    internal val trackedThreads: Cache<String, Long> = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(Duration.ofSeconds(ttlSeconds.coerceAtLeast(60)))
        .maximumSize(maxEntries.coerceAtLeast(1).toLong())
        .build()

    fun track(channelId: String, threadTs: String) {
        if (channelId.isBlank() || threadTs.isBlank()) return
        val expiresAt = (System.currentTimeMillis() / 1000) + ttlSeconds.coerceAtLeast(60)
        trackedThreads.put(key(channelId, threadTs), expiresAt)
    }

    /**
     * 특정 스레드를 추적에서 제거한다 — "나가" 명령 등으로 사용자가 봇을 명시적으로 내보낼 때 사용.
     *
     * 제거 후 해당 스레드에 새 메시지가 와도 봇은 응답하지 않는다 (추적 안 됨 → 무시).
     * 새로운 멘션(`@reactor`)이 오면 자동으로 다시 추적된다.
     */
    fun untrack(channelId: String, threadTs: String) {
        if (channelId.isBlank() || threadTs.isBlank()) return
        trackedThreads.invalidate(key(channelId, threadTs))
    }

    fun isTracked(channelId: String, threadTs: String): Boolean {
        if (channelId.isBlank() || threadTs.isBlank()) return false
        // R292: Caffeine getIfPresent는 expired entry에 대해 자동 null 반환
        // (lazy expiration). 별도 expiry 체크 불필요.
        return trackedThreads.getIfPresent(key(channelId, threadTs)) != null
    }

    private fun key(channelId: String, threadTs: String): String = "$channelId:$threadTs"
}
