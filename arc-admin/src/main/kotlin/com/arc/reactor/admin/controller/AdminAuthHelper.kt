package com.arc.reactor.admin.controller

import com.arc.reactor.auth.AdminAuthorizationSupport
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
 * Delegates policy checks to [AdminAuthorizationSupport].
 */
fun isAdmin(exchange: ServerWebExchange): Boolean {
    return AdminAuthorizationSupport.isAdmin(exchange)
}

fun isAnyAdmin(exchange: ServerWebExchange): Boolean {
    return AdminAuthorizationSupport.isAnyAdmin(exchange)
}

fun currentActor(exchange: ServerWebExchange): String {
    return AdminAuthorizationSupport.currentActor(exchange)
}

fun forbiddenResponse(): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(AdminErrorResponse(error = "Admin access required"))

fun notFoundResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(AdminErrorResponse(error = message))

fun conflictResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.CONFLICT)
        .body(AdminErrorResponse(error = message))

fun badRequestResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.badRequest()
        .body(AdminErrorResponse(error = message))
