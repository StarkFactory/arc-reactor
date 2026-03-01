package com.arc.reactor.auth

import org.springframework.web.server.ServerWebExchange

/**
 * Shared admin authorization helpers for web/admin modules.
 *
 * Policy:
 * - [UserRole.ADMIN] is treated as admin.
 * - null role is treated as non-admin (fail-close).
 */
object AdminAuthorizationSupport {

    fun isAdmin(exchange: ServerWebExchange): Boolean {
        val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
        return role == UserRole.ADMIN
    }

    fun currentActor(exchange: ServerWebExchange): String {
        return (exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "anonymous"
    }
}
