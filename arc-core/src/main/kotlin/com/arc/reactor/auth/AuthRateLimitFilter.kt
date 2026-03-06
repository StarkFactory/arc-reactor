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
 * Rate limiting filter for brute-force-prone authentication endpoints.
 *
 * Limits login/register attempts per IP address to prevent brute-force attacks.
 * Returns HTTP 429 when the limit is exceeded.
 *
 * Authentication is runtime-required, so this filter is always active.
 */
class AuthRateLimitFilter(
    private val maxAttemptsPerMinute: Int = 10
) : WebFilter, Ordered {

    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .maximumSize(10_000)
        .build<String, AtomicInteger>()

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        val method = exchange.request.method

        if (!isRateLimitedAuthPath(path, method.name())) {
            return chain.filter(exchange)
        }

        val key = rateLimitKey(exchange, path)
        if (isBlocked(key)) {
            return tooManyRequests(exchange)
        }

        return chain.filter(exchange)
            .doOnSuccess { handleCompletedAttempt(exchange, key) }
            .doOnError { recordFailure(key) }
    }

    private fun extractClientIp(exchange: ServerWebExchange): String {
        val forwarded = exchange.request.headers.getFirst("X-Forwarded-For")
        if (forwarded != null) {
            return forwarded.split(",").first().trim()
        }
        return exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
    }

    private fun rateLimitKey(exchange: ServerWebExchange, path: String): String {
        return "${extractClientIp(exchange)}:$path"
    }

    private fun isBlocked(key: String): Boolean {
        val failures = cache.getIfPresent(key)?.get() ?: 0
        return failures >= maxAttemptsPerMinute
    }

    private fun handleCompletedAttempt(exchange: ServerWebExchange, key: String) {
        val status = exchange.response.statusCode
        if (status == null || status.is2xxSuccessful) {
            cache.invalidate(key)
            return
        }
        if (status.value() >= 400) {
            recordFailure(key)
        }
    }

    private fun recordFailure(key: String) {
        val count = cache.get(key) { AtomicInteger(0) }.incrementAndGet()
        if (count == maxAttemptsPerMinute) {
            logger.warn {
                "Auth failure limit reached for key=$key ($count/$maxAttemptsPerMinute/min); " +
                    "subsequent attempts will be blocked for 1 minute"
            }
        }
    }

    private fun tooManyRequests(exchange: ServerWebExchange): Mono<Void> {
        exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        exchange.response.headers.set("Retry-After", "60")
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val body = """{"error":"Too many authentication attempts. Please try again later."}"""
        val buffer = exchange.response.bufferFactory().wrap(body.toByteArray())
        return exchange.response.writeWith(Mono.just(buffer))
    }

    private fun isRateLimitedAuthPath(path: String, method: String?): Boolean {
        if (method != "POST") {
            return false
        }
        return path == "/api/auth/login" || path == "/api/auth/register"
    }
}
