package com.arc.reactor.admin.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

data class AdminErrorResponse(
    val error: String,
    val timestamp: String = Instant.now().toString()
)

/**
 * Check if the current request is from an admin user.
 * Uses the same attribute keys as arc-core's JwtAuthWebFilter.
 */
fun isAdmin(exchange: ServerWebExchange): Boolean {
    val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
    // When auth is disabled, role is null → treat as admin (matches arc-core convention)
    return role == null || role == UserRole.ADMIN
}

fun forbiddenResponse(): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(AdminErrorResponse(error = "Admin access required"))
