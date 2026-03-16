package com.arc.reactor.autoconfigure

import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * Request Correlation WebFilter
 *
 * Propagates or generates a unique request correlation ID for every HTTP exchange:
 * - Uses the incoming `X-Request-ID` header value when present.
 * - Generates a random UUID when the header is absent.
 * - Sets `X-Request-ID` on the response so clients can correlate with server logs.
 * - Writes `requestId` into the Reactor context for downstream MDC propagation.
 *
 * Enabled by default. Disable via `arc.reactor.request-correlation.enabled=false`.
 */
class RequestCorrelationFilter : WebFilter, Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val requestId = exchange.request.headers.getFirst(REQUEST_ID_HEADER)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        exchange.response.headers.set(REQUEST_ID_HEADER, requestId)
        return chain.filter(exchange)
            .contextWrite { ctx -> ctx.put(CONTEXT_KEY, requestId) }
    }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-ID"
        const val CONTEXT_KEY = "requestId"
    }
}
