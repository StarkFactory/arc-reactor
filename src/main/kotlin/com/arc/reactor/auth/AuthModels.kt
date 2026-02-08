package com.arc.reactor.auth

import java.time.Instant

/**
 * Authenticated user.
 *
 * @param id Unique identifier (UUID)
 * @param email User email (unique, used for login)
 * @param name Display name
 * @param passwordHash BCrypt-hashed password
 * @param createdAt Account creation timestamp
 */
data class User(
    val id: String,
    val email: String,
    val name: String,
    val passwordHash: String,
    val createdAt: Instant = Instant.now()
)

/**
 * Authentication configuration properties (prefix: arc.reactor.auth).
 *
 * Auth is opt-in: disabled by default. Set `arc.reactor.auth.enabled=true` to activate.
 *
 * @param enabled Whether JWT authentication is active (default: false)
 * @param jwtSecret HMAC secret for JWT signing (required when enabled)
 * @param jwtExpirationMs Token lifetime in milliseconds (default: 24 hours)
 * @param publicPaths URL prefixes that bypass authentication
 */
data class AuthProperties(
    val enabled: Boolean = false,
    val jwtSecret: String = "",
    val jwtExpirationMs: Long = 86_400_000,
    val publicPaths: List<String> = listOf("/api/auth/login", "/api/auth/register")
)
