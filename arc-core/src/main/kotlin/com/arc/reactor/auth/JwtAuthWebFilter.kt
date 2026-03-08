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
 * This filter is always registered in Arc Reactor runtime.
 */
class JwtAuthWebFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val authProperties: AuthProperties,
    private val authProvider: AuthProvider? = null,
    private val tokenRevocationStore: TokenRevocationStore? = null
) : WebFilter, Ordered {

    private val tenantPattern = Regex("^[a-zA-Z0-9_-]{1,64}$")

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 2

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
        if (isRevoked(token)) {
            return unauthorized(exchange)
        }

        // Store userId and role in exchange attributes for downstream controllers
        exchange.attributes[USER_ID_ATTRIBUTE] = userId
        val role = resolveUserRole(userId, token) ?: return unauthorized(exchange)
        exchange.attributes[USER_ROLE_ATTRIBUTE] = role
        val tenantId = resolveTenantId(token)
        exchange.attributes[RESOLVED_TENANT_ID_ATTRIBUTE] = tenantId
        exchange.attributes[USER_EMAIL_ATTRIBUTE] = resolveUserEmail(userId, token)
        return chain.filter(exchange)
    }

    private fun resolveUserEmail(userId: String, token: String): String? {
        val tokenEmail = jwtTokenProvider.extractEmail(token)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (!tokenEmail.isNullOrBlank()) return tokenEmail

        val resolvedUser = authProvider?.getUserById(userId)
        return resolvedUser?.email?.takeIf { it.isNotBlank() }
    }

    private fun resolveUserRole(userId: String, token: String): UserRole? {
        val tokenRole = jwtTokenProvider.extractRole(token) ?: UserRole.USER
        val resolvedUser = authProvider?.getUserById(userId)
        if (authProvider != null && resolvedUser == null) {
            logger.warn { "Rejecting token for missing userId=$userId" }
            return null
        }
        return resolvedUser?.role ?: tokenRole
    }

    private fun isRevoked(token: String): Boolean {
        val tokenId = jwtTokenProvider.extractTokenId(token) ?: return false
        val revoked = tokenRevocationStore?.isRevoked(tokenId) == true
        if (revoked) {
            logger.warn { "Rejected revoked token jti=$tokenId" }
        }
        return revoked
    }

    private fun resolveTenantId(token: String): String {
        val tokenTenant = jwtTokenProvider.extractTenantId(token)
            ?.takeIf { tenantPattern.matches(it) }
        return tokenTenant ?: authProperties.defaultTenantId
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

        /** Key used to store the authenticated user's [UserRole] in ServerWebExchange attributes. */
        const val USER_ROLE_ATTRIBUTE = "userRole"

        /** Key used to store the resolved tenant ID in ServerWebExchange attributes. */
        const val RESOLVED_TENANT_ID_ATTRIBUTE = "resolvedTenantId"

        /** Key used to store the resolved user email in ServerWebExchange attributes. */
        const val USER_EMAIL_ATTRIBUTE = "userEmail"
    }
}
