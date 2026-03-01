package com.arc.reactor.auth

import org.springframework.web.server.ServerWebExchange

/**
 * Shared admin authorization helpers for web/admin modules.
 *
 * Policy:
 * - [UserRole.ADMIN] is treated as admin.
 * - null role is also treated as admin for auth-disabled compatibility.
 */
object AdminAuthorizationSupport {

    fun isAdmin(exchange: ServerWebExchange): Boolean {
        val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
        return role == null || role == UserRole.ADMIN
    }

    fun currentActor(exchange: ServerWebExchange): String {
        return (exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "anonymous"
    }
}
