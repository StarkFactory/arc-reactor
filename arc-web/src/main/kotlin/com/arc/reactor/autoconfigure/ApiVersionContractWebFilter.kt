package com.arc.reactor.autoconfigure

import com.arc.reactor.controller.ErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * API version contract filter.
 *
 * - Requests may specify `X-Arc-Api-Version`.
 * - If omitted, server default is applied.
 * - If provided with an unsupported version, request is rejected with 400.
 * - Response always includes current and supported API version headers.
 */
class ApiVersionContractWebFilter(
    private val objectMapper: ObjectMapper,
    private val currentVersion: String,
    private val supportedVersions: Set<String>
) : WebFilter, Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 2

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        setVersionHeaders(exchange)
        val requestedVersion = exchange.request.headers
            .getFirst(API_VERSION_HEADER)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (requestedVersion == null || requestedVersion in supportedVersions) {
            return chain.filter(exchange)
        }
        return unsupportedVersion(exchange, requestedVersion)
    }

    private fun setVersionHeaders(exchange: ServerWebExchange) {
        exchange.response.headers.set(API_VERSION_HEADER, currentVersion)
        exchange.response.headers.set(API_SUPPORTED_VERSIONS_HEADER, supportedVersions.joinToString(","))
    }

    private fun unsupportedVersion(exchange: ServerWebExchange, requestedVersion: String): Mono<Void> {
        exchange.response.statusCode = HttpStatus.BAD_REQUEST
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val error = ErrorResponse(
            error = "Unsupported API version '$requestedVersion'. " +
                "Supported versions: ${supportedVersions.joinToString(", ")}",
            timestamp = Instant.now().toString()
        )
        val payload = objectMapper.writeValueAsBytes(error)
        val buffer = exchange.response.bufferFactory().wrap(payload)
        return exchange.response.writeWith(Mono.just(buffer))
    }

    companion object {
        const val API_VERSION_HEADER = "X-Arc-Api-Version"
        const val API_SUPPORTED_VERSIONS_HEADER = "X-Arc-Api-Supported-Versions"
    }
}
