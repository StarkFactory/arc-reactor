package com.arc.reactor.auth

import java.time.Instant

/**
 * User role for access control.
 *
 * - [ADMIN] can manage prompt templates and system configuration
 * - [USER] has standard access (chat, persona selection)
 */
enum class UserRole {
    USER, ADMIN
}

/**
 * Authenticated user.
 *
 * @param id Unique identifier (UUID)
 * @param email User email (unique, used for login)
 * @param name Display name
 * @param passwordHash BCrypt-hashed password
 * @param role User role for access control (default: USER)
 * @param createdAt Account creation timestamp
 */
data class User(
    val id: String,
    val email: String,
    val name: String,
    val passwordHash: String,
    val role: UserRole = UserRole.USER,
    val createdAt: Instant = Instant.now()
)

/**
 * Authentication configuration properties (prefix: arc.reactor.auth).
 *
 * Auth is mandatory for Arc Reactor runtime. `arc.reactor.auth.enabled` must stay true.
 *
 * @param enabled Whether JWT authentication is active (default: false; runtime startup enforces true)
 * @param jwtSecret HMAC secret for JWT signing (required when enabled).
 *   Must be at least 32 characters long. Generate with: `openssl rand -base64 32`
 * @param jwtExpirationMs Token lifetime in milliseconds (default: 24 hours)
 * @param publicPaths URL prefixes that bypass authentication
 */
data class AuthProperties(
    val enabled: Boolean = false,
    val jwtSecret: String = "",
    val jwtExpirationMs: Long = 86_400_000,
    val publicPaths: List<String> = listOf(
        "/api/auth/login", "/api/auth/register",
        "/v3/api-docs", "/swagger-ui", "/webjars"
    ),
    /** Maximum auth attempts per minute per IP address (brute-force protection) */
    val loginRateLimitPerMinute: Int = 5
)
