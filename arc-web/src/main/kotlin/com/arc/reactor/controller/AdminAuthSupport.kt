package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange

/**
 * Shared admin authorization helpers for controllers.
 *
 * Policy:
 * [UserRole.ADMIN] is treated as admin and null role is also treated as admin.
 *
 * When auth is disabled, [JwtAuthWebFilter] is not registered and role is null,
 * so all requests are treated as admin for backward compatibility.
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
