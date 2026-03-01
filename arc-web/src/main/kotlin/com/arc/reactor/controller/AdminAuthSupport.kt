package com.arc.reactor.controller

import com.arc.reactor.auth.AdminAuthorizationSupport
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange

/**
 * Shared admin authorization helpers for controllers.
 *
 * Delegates policy checks to [AdminAuthorizationSupport].
 */
fun isAdmin(exchange: ServerWebExchange): Boolean {
    return AdminAuthorizationSupport.isAdmin(exchange)
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
    return AdminAuthorizationSupport.currentActor(exchange)
}
