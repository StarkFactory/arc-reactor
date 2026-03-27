package com.arc.reactor.slack.session

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit

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
    private val trackedThreads: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(maxEntries.toLong().coerceAtLeast(1))
        .expireAfterWrite(ttlSeconds.coerceAtLeast(60), TimeUnit.SECONDS)
        .build()

    fun track(channelId: String, threadTs: String) {
        if (channelId.isBlank() || threadTs.isBlank()) return
        trackedThreads.put(key(channelId, threadTs), true)
    }

    fun isTracked(channelId: String, threadTs: String): Boolean {
        if (channelId.isBlank() || threadTs.isBlank()) return false
        return trackedThreads.getIfPresent(key(channelId, threadTs)) != null
    }

    private fun key(channelId: String, threadTs: String): String = "$channelId:$threadTs"
}

