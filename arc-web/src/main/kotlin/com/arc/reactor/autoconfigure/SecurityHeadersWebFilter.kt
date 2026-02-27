package com.arc.reactor.autoconfigure

import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Security Headers WebFilter
 *
 * Adds standard security headers to all HTTP responses:
 * - `X-Content-Type-Options: nosniff` — prevents MIME type sniffing
 * - `X-Frame-Options: DENY` — prevents clickjacking
 * - `Content-Security-Policy: default-src 'self'` — restricts resource loading
 * - `X-XSS-Protection: 0` — disables legacy XSS filter (modern best practice)
 * - `Referrer-Policy: strict-origin-when-cross-origin` — limits referrer info
 * - `Strict-Transport-Security: max-age=31536000; includeSubDomains` — enforces HTTPS
 *
 * Enabled by default. Disable via `arc.reactor.security-headers.enabled=false`.
 */
class SecurityHeadersWebFilter : WebFilter, Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        exchange.response.headers.apply {
            set("X-Content-Type-Options", "nosniff")
            set("X-Frame-Options", "DENY")
            set("Content-Security-Policy", "default-src 'self'")
            set("X-XSS-Protection", "0")
            set("Referrer-Policy", "strict-origin-when-cross-origin")
            set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
        return chain.filter(exchange)
    }
}
