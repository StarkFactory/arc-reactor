package com.arc.reactor.auth

import org.springframework.web.server.ServerWebExchange

/**
 * Shared admin authorization helpers for web/admin modules.
 *
 * Policy:
 * - [isAdmin] checks developer-level admin access.
 * - [isAnyAdmin] checks manager or developer admin access.
 * - null role is treated as non-admin (fail-close).
 */
object AdminAuthorizationSupport {

    /**
     * Developer/admin control-surface access.
     */
    fun isAdmin(exchange: ServerWebExchange): Boolean {
        val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
        return role?.isDeveloperAdmin() == true
    }

    /**
     * Broad admin access, including manager-only dashboards.
     */
    fun isAnyAdmin(exchange: ServerWebExchange): Boolean {
        val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
        return role?.isAnyAdmin() == true
    }

    fun currentActor(exchange: ServerWebExchange): String {
        return (exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "anonymous"
    }
}
