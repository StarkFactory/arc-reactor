package com.arc.reactor.slack.service

import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Slack users.info API를 통해 사용자 표시 이름을 조회하고 Caffeine 캐시로 관리한다.
 *
 * 멀티유저 스레드에서 발화자를 구분하기 위해 사용한다.
 * 조회 실패 시 userId를 그대로 반환하여 항상 non-null을 보장한다.
 */
class SlackUserNameResolver(
    botToken: String,
    private val cacheTtlSeconds: Long = DEFAULT_CACHE_TTL_SECONDS,
    private val cacheMaxEntries: Int = DEFAULT_CACHE_MAX_ENTRIES,
    private val webClient: WebClient = WebClient.builder()
        .baseUrl("https://slack.com/api")
        .defaultHeader("Authorization", "Bearer $botToken")
        .defaultHeader("Content-Type", "application/json; charset=utf-8")
        .build()
) {

    private val cache = Caffeine.newBuilder()
        .maximumSize(cacheMaxEntries.toLong())
        .expireAfterWrite(Duration.ofSeconds(cacheTtlSeconds))
        .build<String, String>()

    private val cacheMutex = Mutex()

    /**
     * 사용자 표시 이름을 반환한다.
     * display_name → real_name → name → userId 순으로 폴백.
     */
    suspend fun resolveName(userId: String): String {
        val normalizedUserId = userId.trim()
        if (normalizedUserId.isBlank()) return userId

        cache.getIfPresent(normalizedUserId)?.let { return it }

        return cacheMutex.withLock {
            cache.getIfPresent(normalizedUserId)?.let { return@withLock it }
            val resolved = fetchDisplayName(normalizedUserId) ?: normalizedUserId
            cache.put(normalizedUserId, resolved)
            resolved
        }
    }

    private suspend fun fetchDisplayName(userId: String): String? {
        return try {
            val response = webClient.post()
                .uri("/users.info")
                .bodyValue(mapOf("user" to userId))
                .retrieve()
                .awaitBody<SlackUserInfoResponse>()

            if (!response.ok) {
                logger.debug { "사용자 이름 조회 실패: userId=$userId error=${response.error}" }
                return null
            }

            val profile = response.user?.profile
            profile?.displayName?.trim()?.takeIf { it.isNotBlank() }
                ?: response.user?.realName?.trim()?.takeIf { it.isNotBlank() }
                ?: response.user?.name?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.debug(e) { "사용자 이름 조회 예외: userId=$userId" }
            null
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SlackUserInfoResponse(
        val ok: Boolean = false,
        val error: String? = null,
        val user: SlackUserPayload? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SlackUserPayload(
        val name: String? = null,
        val realName: String? = null,
        val profile: SlackProfilePayload? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SlackProfilePayload(
        val displayName: String? = null
    )

    companion object {
        private const val DEFAULT_CACHE_TTL_SECONDS = 3_600L
        private const val DEFAULT_CACHE_MAX_ENTRIES = 20_000
    }
}
