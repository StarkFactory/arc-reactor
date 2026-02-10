package com.arc.reactor.auth

import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Rate limiting filter for authentication endpoints.
 *
 * Limits login/register attempts per IP address to prevent brute-force attacks.
 * Returns HTTP 429 when the limit is exceeded.
 *
 * Only registered when auth is enabled.
 */
class AuthRateLimitFilter(
    private val maxAttemptsPerMinute: Int = 5
) : WebFilter, Ordered {

    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .maximumSize(10_000)
        .build<String, AtomicInteger>()

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.uri.path

        if (!path.startsWith("/api/auth/")) {
            return chain.filter(exchange)
        }

        val ip = extractClientIp(exchange)
        val counter = cache.get(ip) { AtomicInteger(0) }
        val count = counter.incrementAndGet()

        if (count > maxAttemptsPerMinute) {
            logger.warn { "Auth rate limit exceeded for IP=$ip ($count/$maxAttemptsPerMinute/min)" }
            return tooManyRequests(exchange)
        }

        return chain.filter(exchange)
    }

    private fun extractClientIp(exchange: ServerWebExchange): String {
        val forwarded = exchange.request.headers.getFirst("X-Forwarded-For")
        if (forwarded != null) {
            return forwarded.split(",").first().trim()
        }
        return exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
    }

    private fun tooManyRequests(exchange: ServerWebExchange): Mono<Void> {
        exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val body = """{"error":"Too many authentication attempts. Please try again later."}"""
        val buffer = exchange.response.bufferFactory().wrap(body.toByteArray())
        return exchange.response.writeWith(Mono.just(buffer))
    }
}
