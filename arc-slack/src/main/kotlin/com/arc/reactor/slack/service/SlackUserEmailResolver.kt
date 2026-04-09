package com.arc.reactor.slack.service

import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * users.info API를 통해 Slack 사용자 이메일을 조회하고 인메모리 캐싱한다.
 *
 * 최선 노력(best-effort) 설계:
 * - 조회 실패 또는 이메일 부재 시 null을 반환
 * - CancellationException 외에는 호출자에게 예외를 전파하지 않음
 * - Mutex를 사용한 캐시 미스 시 단일 조회 보장 (thundering herd 방지)
 *
 * @param botToken Slack Bot User OAuth 토큰
 * @param enabled 활성화 여부 (false이면 항상 null 반환)
 * @param cacheTtlSeconds 캐시 TTL (초)
 * @param cacheMaxEntries 최대 캐시 엔트리 수
 */
class SlackUserEmailResolver(
    botToken: String,
    private val enabled: Boolean = true,
    private val cacheTtlSeconds: Long = DEFAULT_CACHE_TTL_SECONDS,
    private val cacheMaxEntries: Int = DEFAULT_CACHE_MAX_ENTRIES,
    private val webClient: WebClient = WebClient.builder()
        .baseUrl("https://slack.com/api")
        .defaultHeader("Authorization", "Bearer $botToken")
        .build()
) {

    private val cache = ConcurrentHashMap<String, CachedEmail>()
    private val cacheMutex = Mutex()

    suspend fun resolveEmail(userId: String): String? {
        if (!enabled) return null
        val normalizedUserId = userId.trim()
        if (normalizedUserId.isBlank()) return null

        val now = System.currentTimeMillis()
        cache[normalizedUserId]
            ?.takeIf { it.expiresAtMs > now }
            ?.let { return it.email }

        return cacheMutex.withLock {
            val currentTime = System.currentTimeMillis()
            cache[normalizedUserId]
                ?.takeIf { it.expiresAtMs > currentTime }
                ?.let { return@withLock it.email }

            val resolved = fetchEmail(normalizedUserId) ?: return@withLock null
            putCache(normalizedUserId, resolved, currentTime)
            resolved
        }
    }

    private suspend fun fetchEmail(userId: String): String? {
        return try {
            val response = webClient.get()
                .uri("/users.info?user={userId}", userId)
                .retrieve()
                .awaitBody<SlackUsersInfoResponse>()

            if (!response.ok) {
                logger.warn { "Slack 이메일 조회 실패: userId=$userId error=${response.error}" }
                return null
            }

            response.user?.profile?.email
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Slack users.info 조회 실패: userId=$userId" }
            null
        }
    }

    private fun putCache(userId: String, email: String, nowMs: Long) {
        if (cache.size >= cacheMaxEntries) {
            evictOneEntry()
        }
        val ttlMs = cacheTtlSeconds.coerceAtLeast(1L) * 1_000L
        cache[userId] = CachedEmail(email = email, expiresAtMs = nowMs + ttlMs)
    }

    private fun evictOneEntry() {
        val now = System.currentTimeMillis()
        val expiredKey = cache.entries.firstOrNull { it.value.expiresAtMs <= now }?.key
        if (expiredKey != null) {
            cache.remove(expiredKey)
            return
        }
        cache.keys.firstOrNull()?.let { cache.remove(it) }
    }

    private data class CachedEmail(
        val email: String,
        val expiresAtMs: Long
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SlackUsersInfoResponse(
        val ok: Boolean = false,
        val error: String? = null,
        val user: SlackUserPayload? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SlackUserPayload(
        val profile: SlackUserProfilePayload? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SlackUserProfilePayload(
        val email: String? = null
    )

    companion object {
        private const val DEFAULT_CACHE_TTL_SECONDS = 3_600L
        private const val DEFAULT_CACHE_MAX_ENTRIES = 20_000
    }
}
