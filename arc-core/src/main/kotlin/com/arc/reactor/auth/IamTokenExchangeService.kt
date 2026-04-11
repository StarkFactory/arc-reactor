package com.arc.reactor.auth

import com.arc.reactor.support.throwIfCancellation
import io.jsonwebtoken.Jwts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

private val logger = KotlinLogging.logger {}

/**
 * IAM 연동 설정 속성 (접두사: arc.reactor.auth.iam)
 *
 * @param enabled IAM 토큰 교환 활성화 여부
 * @param baseUrl IAM 서버 URL (예: http://localhost:9090)
 * @param issuer IAM 토큰의 issuer claim (검증용)
 * @param autoCreateUser IAM 사용자가 arc-reactor에 없을 때 자동 생성 여부
 * @param defaultRole 자동 생성 사용자의 기본 역할
 */
data class IamProperties(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val issuer: String = "aslan-iam",
    val autoCreateUser: Boolean = true,
    val defaultRole: UserRole = UserRole.USER
)

/**
 * aslan-iam RS256 토큰을 검증하고 arc-reactor 사용자로 매핑하는 서비스.
 *
 * ## 동작 방식
 * 1. aslan-iam의 `/api/auth/public-key` 에서 RSA 공개 키를 가져온다 (lazy, cached)
 * 2. 수신된 RS256 JWT의 서명과 issuer를 검증한다
 * 3. email claim으로 기존 arc-reactor 사용자를 조회하거나 자동 생성한다
 * 4. arc-reactor HS256 JWT를 발급한다
 *
 * 주의: WebFlux 환경에서 리액터 스레드 블로킹을 피하기 위해
 * java.net.http.HttpClient를 사용한다 (별도 스레드 풀에서 실행).
 *
 * R324 fix: 기존 구현은 `fun exchange(...)`가 non-suspend라 `AuthController.exchange`에서
 * 직접 호출 시 `HttpClient.send()`(blocking) 및 JDBC `userStore.findByEmail`(blocking)이
 * reactor Netty 이벤트 루프 스레드를 최대 5초간 블로킹했다. 동시 요청 N개 → 이벤트 루프
 * 고갈 → 전체 API 지연. `suspend fun`으로 전환하고 본문 전체를 `withContext(Dispatchers.IO)`
 * 로 감싸 블로킹 I/O를 IO 디스패처로 오프로드한다.
 */
class IamTokenExchangeService(
    private val iamProperties: IamProperties,
    private val userStore: UserStore,
    private val jwtTokenProvider: JwtTokenProvider
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val objectMapper = jacksonObjectMapper()
    private val cachedPublicKey = AtomicReference<PublicKey>()

    /**
     * R324 fix: public key 캐시 갱신을 직렬화하기 위한 락.
     * `AtomicReference` 단독으로는 `get()` null → HTTP fetch → `set()` 사이에 N개 스레드가
     * 동시 진입하여 모두 HTTP 호출하는 thundering herd가 발생할 수 있었다. double-checked
     * synchronized 블록으로 첫 fetch를 단일 스레드로 제한한다. 이후 캐시 히트는 synchronized
     * 바깥에서 lock-free 경로로 빠진다.
     */
    private val publicKeyLock = Any()

    /** 서비스 초기화 시 공개 키를 미리 가져온다. */
    fun prefetchPublicKey() {
        try {
            getPublicKey()
            logger.info { "IAM public key pre-fetched from ${iamProperties.baseUrl}" }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "IAM public key pre-fetch failed (will retry on first exchange): ${e.javaClass.simpleName}" }
        }
    }

    /**
     * IAM 토큰을 arc-reactor 토큰으로 교환한다.
     *
     * R324 fix: `suspend fun`으로 전환하고 본문을 `withContext(Dispatchers.IO)`로 감싸
     * 블로킹 I/O(`HttpClient.send`, `userStore.findByEmail` JDBC)가 reactor 이벤트 루프를
     * 블로킹하지 않도록 한다. 호출자 `AuthController.exchange`도 `suspend`로 전환됨.
     */
    suspend fun exchange(iamToken: String): ExchangeResult? = withContext(Dispatchers.IO) {
        val publicKey = getPublicKey() ?: run {
            logger.error { "Failed to fetch IAM public key from ${iamProperties.baseUrl}" }
            return@withContext null
        }

        val claims = try {
            Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(iamProperties.issuer)
                .build()
                .parseSignedClaims(iamToken)
                .payload
        } catch (e: Exception) {
            e.throwIfCancellation()
            // R324 fix: `e.message`가 Jwts parser 내부 세부 정보(토큰 구조, 검증 단계)를
            // 노출할 수 있어 class simpleName만 기록. CLAUDE.md Gotcha #9 정신.
            logger.warn { "IAM token verification failed: ${e.javaClass.simpleName}" }
            return@withContext null
        }

        val iamUserId = claims.subject
            ?: run { logger.warn { "IAM token missing sub claim" }; return@withContext null }
        val email = claims.get("email", String::class.java)
        val roles = claims.get("roles", List::class.java)?.filterIsInstance<String>() ?: emptyList()

        val resolvedEmail = email ?: "$iamUserId@iam.local"

        val user = userStore.findByEmail(resolvedEmail) ?: run {
            if (!iamProperties.autoCreateUser) {
                logger.info { "IAM user $resolvedEmail not found and auto-create disabled" }
                return@withContext null
            }
            val role = resolveRole(roles)
            val newUser = User(
                id = UUID.randomUUID().toString(),
                email = resolvedEmail,
                name = email?.substringBefore('@') ?: iamUserId,
                passwordHash = "",
                role = role
            )
            logger.info { "Auto-creating arc-reactor user for IAM user: $resolvedEmail (role=$role)" }
            userStore.save(newUser)
        }

        val arcToken = jwtTokenProvider.createToken(user)
        ExchangeResult(token = arcToken, user = user)
    }

    private fun resolveRole(iamRoles: List<String>): UserRole {
        return when {
            iamRoles.any { it.equals("ROLE_ADMIN", ignoreCase = true) } -> UserRole.ADMIN
            iamRoles.any { it.equals("ROLE_MANAGER", ignoreCase = true) } -> UserRole.ADMIN_MANAGER
            iamRoles.any { it.equals("ROLE_DEVELOPER", ignoreCase = true) } -> UserRole.ADMIN_DEVELOPER
            else -> iamProperties.defaultRole
        }
    }

    /**
     * 공개 키를 캐시에서 반환하거나 IAM 서버에서 가져온다.
     *
     * R324 fix: `publicKeyLock`에 대해 double-checked locking을 적용하여 thundering herd를
     * 방지한다. 캐시 히트는 lock-free 경로로 즉시 반환되고, 캐시 미스 시에만 synchronized
     * 블록에 진입하여 단일 스레드가 fetch한 뒤 나머지 대기 스레드는 캐시된 결과를 재사용한다.
     */
    private fun getPublicKey(): PublicKey? {
        cachedPublicKey.get()?.let { return it }

        synchronized(publicKeyLock) {
            // double-checked: 다른 스레드가 이미 fetch했을 수 있다
            cachedPublicKey.get()?.let { return it }

            return try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("${iamProperties.baseUrl}/api/auth/public-key"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    logger.error { "IAM public key endpoint returned ${response.statusCode()}" }
                    return null
                }

                val body = objectMapper.readValue<Map<String, String>>(response.body())
                body["publicKey"]?.let { pem ->
                    val key = parsePemPublicKey(pem)
                    cachedPublicKey.set(key)
                    key
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Failed to fetch IAM public key" }
                null
            }
        }
    }

    fun invalidatePublicKey() {
        cachedPublicKey.set(null)
    }

    companion object {
        fun parsePemPublicKey(pem: String): PublicKey {
            val base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val keyBytes = Base64.getDecoder().decode(base64)
            val keySpec = X509EncodedKeySpec(keyBytes)
            return KeyFactory.getInstance("RSA").generatePublic(keySpec)
        }
    }
}

data class ExchangeResult(val token: String, val user: User)
