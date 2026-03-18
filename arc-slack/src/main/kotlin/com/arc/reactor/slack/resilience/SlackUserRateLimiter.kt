package com.arc.reactor.slack.resilience

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

private val logger = KotlinLogging.logger {}

/**
 * 사용자별 슬라이딩 윈도우 레이트 리미터.
 *
 * 1분 윈도우 내에서 사용자당 최대 요청 수를 제한하여,
 * 단일 사용자의 과도한 요청으로 인한 토큰 할당량 소진을 방지한다.
 *
 * @param maxRequestsPerMinute 분당 최대 요청 수 (기본 10)
 * @param maxEntries 최대 추적 사용자 수 (기본 50000)
 */
class SlackUserRateLimiter(
    private val maxRequestsPerMinute: Int = 10,
    private val maxEntries: Int = 50000
) {

    private val windowMs = 60_000L
    private val userWindows = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()

    /**
     * 사용자 요청을 허용할지 판별한다.
     *
     * @param userId Slack 사용자 ID
     * @return true이면 허용, false이면 레이트 초과로 거부
     */
    fun tryAcquire(userId: String): Boolean {
        cleanup()
        val now = System.currentTimeMillis()
        val window = userWindows.computeIfAbsent(userId) { ConcurrentLinkedDeque() }

        purgeExpired(window, now)

        if (window.size >= maxRequestsPerMinute) {
            logger.warn { "사용자 레이트 리밋 초과: userId=$userId, limit=$maxRequestsPerMinute/min" }
            return false
        }

        window.addLast(now)
        return true
    }

    /** 만료된 타임스탬프를 윈도우에서 제거한다. */
    private fun purgeExpired(window: ConcurrentLinkedDeque<Long>, now: Long) {
        val cutoff = now - windowMs
        while (true) {
            val first = window.peekFirst() ?: break
            if (first < cutoff) {
                window.pollFirst()
            } else {
                break
            }
        }
    }

    /** maxEntries 초과 시 오래된 엔트리를 정리한다. */
    private fun cleanup() {
        if (userWindows.size <= maxEntries) return

        val now = System.currentTimeMillis()
        val cutoff = now - windowMs
        val iterator = userWindows.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            purgeExpired(entry.value, now)
            if (entry.value.isEmpty() || (entry.value.peekLast() ?: 0L) < cutoff) {
                iterator.remove()
            }
        }
    }
}
