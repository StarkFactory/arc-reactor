package com.arc.reactor.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import mu.KotlinLogging
import java.util.Date
import javax.crypto.SecretKey

private val logger = KotlinLogging.logger {}

/**
 * JWT Token Provider
 *
 * Creates and validates JWT tokens using JJWT (HS256).
 *
 * Claims:
 * - `sub` : userId
 * - `email` : user email
 * - `iat` : issued at
 * - `exp` : expiration
 */
class JwtTokenProvider(private val authProperties: AuthProperties) {

    private val secretKey: SecretKey = Keys.hmacShaKeyFor(
        authProperties.jwtSecret.toByteArray()
    )

    /**
     * Create a JWT token for the given user.
     *
     * @return Signed JWT string
     */
    fun createToken(user: User): String {
        val now = Date()
        val expiry = Date(now.time + authProperties.jwtExpirationMs)

        return Jwts.builder()
            .subject(user.id)
            .claim("email", user.email)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(secretKey)
            .compact()
    }

    /**
     * Validate a JWT token and extract the userId.
     *
     * @return userId (subject) if valid, null if invalid or expired
     */
    fun validateToken(token: String): String? {
        return try {
            val claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .payload
            claims.subject
        } catch (e: Exception) {
            logger.debug { "JWT validation failed: ${e.message}" }
            null
        }
    }
}
