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

fun forbiddenResponse(): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(AdminErrorResponse(error = "Admin access required"))
