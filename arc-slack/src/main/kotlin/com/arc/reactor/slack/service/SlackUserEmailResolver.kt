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
 * Resolves Slack user email via users.info and caches it in-memory.
 *
 * Best-effort by design:
 * - returns null when lookup fails or email is absent
 * - never throws to callers except cancellation
 */
class SlackUserEmailResolver(
    botToken: String,
    private val enabled: Boolean = true,
    private val cacheTtlSeconds: Long = DEFAULT_CACHE_TTL_SECONDS,
    private val cacheMaxEntries: Int = DEFAULT_CACHE_MAX_ENTRIES,
    private val webClient: WebClient = WebClient.builder()
        .baseUrl("https://slack.com/api")
        .defaultHeader("Authorization", "Bearer $botToken")
        .defaultHeader("Content-Type", "application/json; charset=utf-8")
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
            val response = webClient.post()
                .uri("/users.info")
                .bodyValue(mapOf("user" to userId))
                .retrieve()
                .awaitBody<SlackUsersInfoResponse>()

            if (!response.ok) {
                logger.warn { "Failed to resolve Slack email for userId=$userId error=${response.error}" }
                return null
            }

            response.user?.profile?.email
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Slack users.info lookup failed for userId=$userId" }
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
