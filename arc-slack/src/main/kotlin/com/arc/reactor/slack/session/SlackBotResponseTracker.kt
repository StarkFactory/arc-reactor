package com.arc.reactor.slack.session

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 봇 응답 메시지를 추적하여 이모지 리액션을 원래 대화 세션과 연결하는 추적기.
 *
 * 피드백 수집을 위해 봇이 전송한 메시지의 채널 ID + 타임스탬프를 키로,
 * 세션 ID와 사용자 프롬프트를 값으로 저장한다.
 * [ttlSeconds] 경과 후 자동으로 만료된다.
 *
 * @param ttlSeconds 엔트리 보관 기간 (초, 기본 24시간)
 * @param maxEntries 최대 보관 엔트리 수
 * @see DefaultSlackEventHandler
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
        val victims = entries.entries
            .asSequence()
            .sortedBy { it.value.expiresAt }
            .take(overflow)
            .toList()
        for (victim in victims) {
            entries.remove(victim.key)
        }
    }

    private fun key(channelId: String, messageTs: String): String = "$channelId:$messageTs"
}

/** 추적 중인 봇 응답 정보. */
data class TrackedBotResponse(
    val sessionId: String,
    val userPrompt: String,
    val expiresAt: Long
)
