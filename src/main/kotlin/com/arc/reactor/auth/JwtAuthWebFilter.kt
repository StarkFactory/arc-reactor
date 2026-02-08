package com.arc.reactor.auth

import mu.KotlinLogging
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * JWT Authentication WebFilter
 *
 * Intercepts all incoming requests and validates the Bearer token.
 * On success, stores the userId in [ServerWebExchange.getAttributes] for downstream use.
 * Public paths (login, register) are allowed through without a token.
 *
 * This filter is only registered when `arc.reactor.auth.enabled=true`.
 */
class JwtAuthWebFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val authProperties: AuthProperties
) : WebFilter, Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.uri.path

        // Public paths bypass authentication
        if (isPublicPath(path)) {
            return chain.filter(exchange)
        }

        // Extract Bearer token
        val authHeader = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange)
        }

        val token = authHeader.substring(7)
        val userId = jwtTokenProvider.validateToken(token)
        if (userId == null) {
            return unauthorized(exchange)
        }

        // Store userId in exchange attributes for downstream controllers
        exchange.attributes[USER_ID_ATTRIBUTE] = userId
        return chain.filter(exchange)
    }

    private fun isPublicPath(path: String): Boolean {
        return authProperties.publicPaths.any { path.startsWith(it) }
    }

    private fun unauthorized(exchange: ServerWebExchange): Mono<Void> {
        logger.debug { "Unauthorized request: ${exchange.request.method} ${exchange.request.uri.path}" }
        exchange.response.statusCode = HttpStatus.UNAUTHORIZED
        return exchange.response.setComplete()
    }

    companion object {
        /** Key used to store the authenticated userId in ServerWebExchange attributes. */
        const val USER_ID_ATTRIBUTE = "userId"
    }
}
