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
 * Broad admin access check for dashboard-like read surfaces.
 */
fun isAnyAdmin(exchange: ServerWebExchange): Boolean {
    return AdminAuthorizationSupport.isAnyAdmin(exchange)
}

/**
 * Standard 403 Forbidden response for non-admin users.
 */
fun forbiddenResponse(): ResponseEntity<Any> {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ErrorResponse(error = "Admin access required", timestamp = java.time.Instant.now().toString()))
}

/**
 * Standard 404 Not Found response with descriptive message.
 */
fun notFoundResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse(error = message, timestamp = java.time.Instant.now().toString()))

/**
 * Standard 409 Conflict response with descriptive message.
 */
fun conflictResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse(error = message, timestamp = java.time.Instant.now().toString()))

/**
 * Standard 400 Bad Request response with descriptive message.
 */
fun badRequestResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.badRequest()
        .body(ErrorResponse(error = message, timestamp = java.time.Instant.now().toString()))

/**
 * Resolve current actor id for admin-audit logs.
 */
fun currentActor(exchange: ServerWebExchange): String {
    return AdminAuthorizationSupport.currentActor(exchange)
}
