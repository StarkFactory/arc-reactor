package com.arc.reactor.auth

import java.security.MessageDigest
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

    fun maskedAdminAccountRef(actor: String?): String {
        val normalized = actor?.trim()?.takeIf { it.isNotBlank() } ?: return "admin-account:unknown"
        if (normalized == "anonymous") return "admin-account:anonymous"
        return "admin-account:${sha256Hex(normalized).take(12)}"
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
