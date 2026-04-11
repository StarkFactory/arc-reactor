package com.arc.reactor.slack.session

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import java.time.Duration

/**
 * 봇 응답 메시지를 추적하여 이모지 리액션을 원래 대화 세션과 연결하는 추적기.
 *
 * 피드백 수집을 위해 봇이 전송한 메시지의 채널 ID + 타임스탬프를 키로,
 * 세션 ID와 사용자 프롬프트를 값으로 저장한다.
 * [ttlSeconds] 경과 후 자동으로 만료된다.
 *
 * R292 fix: ConcurrentHashMap + 수동 cleanup → Caffeine bounded cache (CLAUDE.md 준수).
 * 이전 구현은 ConcurrentHashMap 사용 + sortedBy 기반 eviction이 hot path에서 race window를
 * 가지고 있었다. Caffeine의 expireAfterWrite + maximumSize로 자동/atomic eviction.
 *
 * @param ttlSeconds 엔트리 보관 기간 (초, 기본 24시간)
 * @param maxEntries 최대 보관 엔트리 수
 * @see DefaultSlackEventHandler
 */
class SlackBotResponseTracker(
    private val ttlSeconds: Long = 86400,
    maxEntries: Int = 50000,
    ticker: Ticker = Ticker.systemTicker()
) {
    /**
     * R292: Caffeine bounded cache. expireAfterWrite로 TTL 자동 만료, maximumSize로
     * LRU eviction 자동 처리. [TrackedBotResponse.expiresAt] 필드는 호출자에게 정보
     * 제공용으로 보존하지만, 실제 만료는 Caffeine이 책임진다.
     *
     * `internal`로 노출하여 테스트가 [Cache.cleanUp]을 강제 호출할 수 있게 한다.
     */
    internal val entries: Cache<String, TrackedBotResponse> = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(Duration.ofSeconds(ttlSeconds.coerceAtLeast(1)))
        .maximumSize(maxEntries.coerceAtLeast(1).toLong())
        .build()

    fun track(channelId: String, messageTs: String, sessionId: String, userPrompt: String) {
        if (channelId.isBlank() || messageTs.isBlank()) return
        val now = System.currentTimeMillis() / 1000
        entries.put(
            key(channelId, messageTs),
            TrackedBotResponse(
                sessionId = sessionId,
                userPrompt = userPrompt,
                expiresAt = now + ttlSeconds.coerceAtLeast(1)
            )
        )
    }

    fun lookup(channelId: String, messageTs: String): TrackedBotResponse? {
        if (channelId.isBlank() || messageTs.isBlank()) return null
        // R292: Caffeine getIfPresent는 expired entry에 대해 자동 null 반환
        return entries.getIfPresent(key(channelId, messageTs))
    }

    private fun key(channelId: String, messageTs: String): String = "$channelId:$messageTs"
}

/** 추적 중인 봇 응답 정보. */
data class TrackedBotResponse(
    val sessionId: String,
    val userPrompt: String,
    val expiresAt: Long
)
