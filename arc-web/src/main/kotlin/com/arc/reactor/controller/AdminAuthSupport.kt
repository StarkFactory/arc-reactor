package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange

/**
 * Shared admin authorization helpers for controllers.
 *
 * When auth is disabled, [JwtAuthWebFilter] is not registered so the role attribute is null.
 * In that case all operations are allowed (consistent with frontend: `!isAuthRequired || isAdmin`).
 */
fun isAdmin(exchange: ServerWebExchange): Boolean {
    val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
    return role == null || role == UserRole.ADMIN
}

/**
 * Standard 403 Forbidden response for non-admin users.
 */
fun forbiddenResponse(): ResponseEntity<Any> {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ErrorResponse(error = "Admin access required", timestamp = java.time.Instant.now().toString()))
}

/**
 * Resolve current actor id for admin-audit logs.
 */
fun currentActor(exchange: ServerWebExchange): String {
    return (exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String)
        ?.takeIf { it.isNotBlank() }
        ?: "anonymous"
}
