package com.arc.reactor.autoconfigure

import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Security Headers WebFilter
 *
 * Adds standard security headers to HTTP responses:
 * - `X-Content-Type-Options: nosniff` — prevents MIME type sniffing
 * - `X-Frame-Options: DENY` — prevents clickjacking
 * - `Content-Security-Policy: default-src 'self'` — restricts resource loading (API paths only)
 * - `X-XSS-Protection: 0` — disables legacy XSS filter (modern best practice)
 * - `Referrer-Policy: strict-origin-when-cross-origin` — limits referrer info
 * - `Strict-Transport-Security: max-age=31536000; includeSubDomains` — enforces HTTPS
 *
 * Swagger UI paths (`/swagger-ui`, `/v3/api-docs`, `/webjars`) use a relaxed CSP
 * that permits inline styles required by the UI.
 *
 * Enabled by default. Disable via `arc.reactor.security-headers.enabled=false`.
 */
class SecurityHeadersWebFilter : WebFilter, Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        val csp = if (isSwaggerPath(path)) {
            "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'"
        } else {
            "default-src 'self'"
        }
        exchange.response.headers.apply {
            set("X-Content-Type-Options", "nosniff")
            set("X-Frame-Options", "DENY")
            set("Content-Security-Policy", csp)
            set("X-XSS-Protection", "0")
            set("Referrer-Policy", "strict-origin-when-cross-origin")
            set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
        return chain.filter(exchange)
    }

    private fun isSwaggerPath(path: String): Boolean =
        path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs") || path.startsWith("/webjars")
}
