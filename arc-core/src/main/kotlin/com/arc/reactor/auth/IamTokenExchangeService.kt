package com.arc.reactor.auth

import io.jsonwebtoken.Jwts
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

    /** 서비스 초기화 시 공개 키를 미리 가져온다. */
    fun prefetchPublicKey() {
        try {
            getPublicKey()
            logger.info { "IAM public key pre-fetched from ${iamProperties.baseUrl}" }
        } catch (e: Exception) {
            logger.warn { "IAM public key pre-fetch failed (will retry on first exchange): ${e.message}" }
        }
    }

    fun exchange(iamToken: String): ExchangeResult? {
        val publicKey = getPublicKey() ?: run {
            logger.error { "Failed to fetch IAM public key from ${iamProperties.baseUrl}" }
            return null
        }

        val claims = try {
            Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(iamProperties.issuer)
                .build()
                .parseSignedClaims(iamToken)
                .payload
        } catch (e: Exception) {
            logger.warn { "IAM token verification failed: ${e.message}" }
            return null
        }

        val iamUserId = claims.subject
            ?: run { logger.warn { "IAM token missing sub claim" }; return null }
        val email = claims.get("email", String::class.java)
        val roles = claims.get("roles", List::class.java)?.filterIsInstance<String>() ?: emptyList()

        val resolvedEmail = email ?: "$iamUserId@iam.local"

        val user = userStore.findByEmail(resolvedEmail) ?: run {
            if (!iamProperties.autoCreateUser) {
                logger.info { "IAM user $resolvedEmail not found and auto-create disabled" }
                return null
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
        return ExchangeResult(token = arcToken, user = user)
    }

    private fun resolveRole(iamRoles: List<String>): UserRole {
        return when {
            iamRoles.any { it.equals("ROLE_ADMIN", ignoreCase = true) } -> UserRole.ADMIN
            iamRoles.any { it.equals("ROLE_MANAGER", ignoreCase = true) } -> UserRole.ADMIN_MANAGER
            iamRoles.any { it.equals("ROLE_DEVELOPER", ignoreCase = true) } -> UserRole.ADMIN_DEVELOPER
            else -> iamProperties.defaultRole
        }
    }

    private fun getPublicKey(): PublicKey? {
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
            logger.error(e) { "Failed to fetch IAM public key" }
            null
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
