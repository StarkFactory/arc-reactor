package com.arc.reactor.slack.service

import com.arc.reactor.identity.JiraAccountIdResolver
import com.arc.reactor.identity.UserIdentity
import com.arc.reactor.identity.UserIdentityStore
import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
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
 * 조회 우선순위:
 * 1. 인메모리 캐시 (TTL 기반)
 * 2. DB (UserIdentityStore) — 서버 재시작 후에도 캐시 워밍 불필요
 * 3. Slack API (users.info) → 성공 시 DB에 저장
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
 * @param userIdentityStore DB 기반 사용자 신원 저장소 (선택)
 */
class SlackUserEmailResolver(
    botToken: String,
    private val enabled: Boolean = true,
    private val cacheTtlSeconds: Long = DEFAULT_CACHE_TTL_SECONDS,
    private val cacheMaxEntries: Int = DEFAULT_CACHE_MAX_ENTRIES,
    private val webClient: WebClient = WebClient.builder()
        .baseUrl("https://slack.com/api")
        .defaultHeader("Authorization", "Bearer $botToken")
        .build(),
    private val userIdentityStore: UserIdentityStore? = null,
    private val jiraAccountIdResolver: JiraAccountIdResolver? = null
) {

    private val cache = ConcurrentHashMap<String, CachedEmail>()
    private val cacheMutex = Mutex()

    /**
     * Slack User ID로 이메일을 조회한다.
     * 캐시 → DB → Slack API 순서로 조회하며, Slack API 성공 시 DB에 저장한다.
     */
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

            // DB 우선 조회
            val dbIdentity = findFromDb(normalizedUserId)
            if (dbIdentity?.email != null) {
                putCache(normalizedUserId, dbIdentity.email, currentTime)
                return@withLock dbIdentity.email
            }

            // Slack API 폴백
            val profile = fetchProfile(normalizedUserId) ?: return@withLock null
            val email = profile.email
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@withLock null

            putCache(normalizedUserId, email, currentTime)
            saveToDb(normalizedUserId, email, profile.displayName ?: profile.realName)
            email
        }
    }

    /**
     * Slack User ID로 전체 신원 정보를 조회한다.
     * 캐시/DB/Slack API를 거쳐 [UserIdentity]를 반환한다.
     * 이메일 조회 불가 시 null 반환.
     */
    suspend fun resolveIdentity(userId: String): UserIdentity? {
        if (!enabled) return null
        val normalizedUserId = userId.trim()
        if (normalizedUserId.isBlank()) return null

        // DB에 이미 저장된 경우 jiraAccountId 보강 후 반환
        val dbIdentity = findFromDb(normalizedUserId)
        if (dbIdentity != null) {
            return enrichJiraAccountId(dbIdentity)
        }

        // Slack API로 프로필 조회 후 DB 저장
        val profile = fetchProfile(normalizedUserId) ?: return null
        val email = profile.email
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val identity = UserIdentity(
            slackUserId = normalizedUserId,
            email = email,
            displayName = profile.displayName ?: profile.realName
        )
        saveToDb(normalizedUserId, email, identity.displayName)

        // 신규 저장 후 Jira accountId 자동 조회
        return enrichJiraAccountId(identity)
    }

    /**
     * jiraAccountId가 비어 있으면 [JiraAccountIdResolver]로 자동 조회하여 보강한다.
     * 조회 성공 시 DB에도 업데이트된다 (resolveAndStore 내부).
     */
    private suspend fun enrichJiraAccountId(identity: UserIdentity): UserIdentity {
        if (!identity.jiraAccountId.isNullOrBlank()) return identity
        val resolver = jiraAccountIdResolver ?: return identity
        return try {
            val accountId = resolver.resolveAndStore(identity.email, identity.slackUserId)
            if (accountId != null) {
                identity.copy(jiraAccountId = accountId)
            } else {
                identity
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Jira accountId 자동 조회 실패: slackUserId=${identity.slackUserId}" }
            identity
        }
    }

    /** DB에서 사용자 신원 정보를 조회한다. 실패 시 null 반환. */
    private fun findFromDb(userId: String): UserIdentity? {
        val store = userIdentityStore ?: return null
        return try {
            store.findBySlackUserId(userId)
        } catch (e: Exception) {
            logger.warn(e) { "사용자 신원 DB 조회 실패: userId=$userId" }
            null
        }
    }

    /** Slack API 응답을 DB에 저장한다. 실패 시 경고 로그만 남긴다. */
    private fun saveToDb(userId: String, email: String, displayName: String?) {
        val store = userIdentityStore ?: return
        try {
            store.save(
                UserIdentity(
                    slackUserId = userId,
                    email = email,
                    displayName = displayName
                )
            )
            logger.debug { "Slack 사용자 신원 DB 저장 완료: userId=$userId" }
        } catch (e: Exception) {
            logger.warn(e) { "사용자 신원 DB 저장 실패: userId=$userId" }
        }
    }

    /** Slack users.info API에서 프로필 정보를 조회한다. */
    private suspend fun fetchProfile(userId: String): SlackUserProfilePayload? {
        return try {
            val response = webClient.get()
                .uri("/users.info?user={userId}", userId)
                .retrieve()
                .awaitBody<SlackUsersInfoResponse>()

            if (!response.ok) {
                logger.warn { "Slack 이메일 조회 실패: userId=$userId error=${response.error}" }
                return null
            }

            response.user?.profile
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
    internal data class SlackUserProfilePayload(
        val email: String? = null,
        @JsonProperty("display_name")
        val displayName: String? = null,
        @JsonProperty("real_name")
        val realName: String? = null
    )

    companion object {
        private const val DEFAULT_CACHE_TTL_SECONDS = 3_600L
        private const val DEFAULT_CACHE_MAX_ENTRIES = 20_000
    }
}
