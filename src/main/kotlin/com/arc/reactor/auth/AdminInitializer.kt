package com.arc.reactor.auth

import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Creates an initial ADMIN account on server startup from environment variables.
 *
 * Required env vars:
 * - `ARC_REACTOR_AUTH_ADMIN_EMAIL` : admin email
 * - `ARC_REACTOR_AUTH_ADMIN_PASSWORD` : admin password (min 8 chars)
 *
 * Optional:
 * - `ARC_REACTOR_AUTH_ADMIN_NAME` : display name (default: "Admin")
 *
 * If the email already exists in the DB, the initializer does nothing.
 * If the env vars are not set, the initializer is silently skipped.
 */
class AdminInitializer(
    private val userStore: UserStore,
    private val authProvider: AuthProvider,
    private val envReader: (String) -> String? = System::getenv
) {

    @EventListener(ApplicationReadyEvent::class)
    fun initAdmin() {
        val email = envReader("ARC_REACTOR_AUTH_ADMIN_EMAIL")
        val password = envReader("ARC_REACTOR_AUTH_ADMIN_PASSWORD")

        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            logger.debug { "Admin env vars not set, skipping admin initialization" }
            return
        }

        if (userStore.existsByEmail(email)) {
            logger.info { "Admin account already exists: $email" }
            return
        }

        val name = envReader("ARC_REACTOR_AUTH_ADMIN_NAME").takeUnless { it.isNullOrBlank() } ?: "Admin"

        val passwordHash = when (authProvider) {
            is DefaultAuthProvider -> authProvider.hashPassword(password)
            else -> {
                logger.warn { "Custom AuthProvider — cannot hash password for admin seed" }
                return
            }
        }

        val admin = User(
            id = UUID.randomUUID().toString(),
            email = email,
            name = name,
            passwordHash = passwordHash,
            role = UserRole.ADMIN
        )
        userStore.save(admin)
        logger.info { "Initial ADMIN account created: $email" }
    }
}
