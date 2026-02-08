package com.arc.reactor.auth

import mu.KotlinLogging
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

private val logger = KotlinLogging.logger {}

/**
 * Default Authentication Provider
 *
 * Authenticates users against the [UserStore] using BCrypt password hashing.
 * Uses Spring Security Crypto's [BCryptPasswordEncoder] (without pulling in Spring Security).
 *
 * Enterprises can replace this by providing a custom [AuthProvider] bean.
 */
class DefaultAuthProvider(private val userStore: UserStore) : AuthProvider {

    private val passwordEncoder = BCryptPasswordEncoder()

    override fun authenticate(email: String, password: String): User? {
        val user = userStore.findByEmail(email) ?: return null
        return if (passwordEncoder.matches(password, user.passwordHash)) {
            logger.debug { "Authentication successful for: $email" }
            user
        } else {
            logger.debug { "Authentication failed for: $email" }
            null
        }
    }

    override fun getUserById(userId: String): User? = userStore.findById(userId)

    /**
     * Hash a plaintext password using BCrypt.
     */
    fun hashPassword(rawPassword: String): String = passwordEncoder.encode(rawPassword)
}
