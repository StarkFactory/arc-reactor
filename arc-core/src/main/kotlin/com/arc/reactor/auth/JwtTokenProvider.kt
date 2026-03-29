package com.arc.reactor.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import mu.KotlinLogging
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

private val logger = KotlinLogging.logger {}

/**
 * JWT 토큰 제공자
 *
 * JJWT 라이브러리를 사용하여 JWT 토큰을 생성하고 검증한다 (HS256 알고리즘).
 *
 * ## JWT Claims 구조
 * - `sub`: userId (고유 사용자 식별자)
 * - `jti`: 토큰 ID (폐기 추적용)
 * - `email`: 사용자 이메일
 * - `role`: 사용자 역할 (ADMIN, USER 등)
 * - `tenantId`: 테넌트 식별자
 * - `iat`: 발급 시각
 * - `exp`: 만료 시각
 *
 * ## 보안 요구사항
 * JWT 시크릿은 최소 32바이트(256비트)여야 한다 (HS256 요구사항).
 * init 블록에서 이를 검증하여 약한 시크릿 사용을 방지한다.
 *
 * @param authProperties 인증 설정 (시크릿, 만료 시간 등)
 *
 * @see JwtAuthWebFilter JWT 토큰을 검증하는 WebFilter
 * @see AuthProperties 인증 설정 속성
 */
class JwtTokenProvider(private val authProperties: AuthProperties) {

    init {
        // HS256은 최소 256비트(32바이트) 시크릿을 요구한다
        val secretBytes = authProperties.jwtSecret.toByteArray()
        require(secretBytes.size >= 32) {
            "JWT secret must be at least 32 bytes for HS256. " +
            "Current length: ${secretBytes.size} bytes. " +
            "Set a stronger secret via arc.reactor.auth.jwt-secret"
        }
    }

    /** HMAC-SHA256 서명 키 */
    private val secretKey: SecretKey = Keys.hmacShaKeyFor(
        authProperties.jwtSecret.toByteArray()
    )

    /**
     * 주어진 사용자에 대한 JWT 토큰을 생성한다.
     * Claims에 userId(sub), email, role, 발급 시각, 만료 시각을 포함한다.
     *
     * @return 서명된 JWT 문자열
     */
    fun createToken(user: User): String {
        val now = Date()
        val expiry = Date(now.time + authProperties.jwtExpirationMs)

        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(user.id)
            .claim("email", user.email)
            .claim("role", user.role.name)
            .claim("tenantId", authProperties.defaultTenantId)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(secretKey)
            .compact()
    }

    /**
     * JWT 토큰을 검증하고 userId를 추출한다.
     *
     * @return 유효한 경우 userId(subject), 유효하지 않거나 만료된 경우 null
     */
    fun validateToken(token: String): String? {
        return parseClaims(token)?.subject
    }

    /**
     * JWT 토큰을 검증하고 role claim을 추출한다.
     *
     * @return 유효한 경우 [UserRole], 유효하지 않거나 만료된 경우 null
     */
    fun extractRole(token: String): UserRole? {
        return try {
            val claims = parseClaims(token) ?: return null
            val roleName = claims.get("role", String::class.java) ?: return UserRole.USER
            UserRole.valueOf(roleName)
        } catch (e: Exception) {
            logger.debug { "JWT role 추출 실패: ${e.message}" }
            null
        }
    }

    /**
     * JWT 토큰을 검증하고 tenantId claim을 추출한다.
     *
     * @return 유효한 경우 tenantId, 유효하지 않거나 없는 경우 null
     */
    fun extractTenantId(token: String): String? {
        return try {
            val claims = parseClaims(token) ?: return null
            claims.get("tenantId", String::class.java)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.debug { "JWT tenantId 추출 실패: ${e.message}" }
            null
        }
    }

    /**
     * JWT 토큰을 검증하고 email claim을 추출한다.
     *
     * @return 유효한 경우 email, 유효하지 않거나 없는 경우 null
     */
    fun extractEmail(token: String): String? {
        return try {
            val claims = parseClaims(token) ?: return null
            claims.get("email", String::class.java)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.debug { "JWT email 추출 실패: ${e.message}" }
            null
        }
    }

    /**
     * JWT 토큰을 검증하고 accountId claim을 추출한다.
     * "accountId" 또는 "account_id" claim을 모두 확인한다.
     *
     * @return 유효한 경우 accountId, 유효하지 않거나 없는 경우 null
     */
    fun extractAccountId(token: String): String? {
        return try {
            val claims = parseClaims(token) ?: return null
            val candidateClaims = listOf("accountId", "account_id")
            candidateClaims
                .asSequence()
                .mapNotNull { claims.get(it, String::class.java) }
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
        } catch (e: Exception) {
            logger.debug { "JWT accountId 추출 실패: ${e.message}" }
            null
        }
    }

    /** JWT 토큰에서 토큰 ID(jti)를 추출한다 */
    fun extractTokenId(token: String): String? {
        return parseClaims(token)?.id?.trim()?.takeIf { it.isNotBlank() }
    }

    /** JWT 토큰에서 만료 시각을 추출한다 */
    fun extractExpiration(token: String): Instant? {
        return parseClaims(token)?.expiration?.toInstant()
    }

    /**
     * JWT 토큰을 파싱하고 서명을 검증하여 Claims를 반환한다.
     * 파싱 실패 시(만료, 변조 등) null을 반환한다.
     */
    private fun parseClaims(token: String): io.jsonwebtoken.Claims? {
        return try {
            Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (e: Exception) {
            logger.debug { "JWT 파싱 실패: ${e.message}" }
            null
        }
    }
}
