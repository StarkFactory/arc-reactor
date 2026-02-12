package com.arc.reactor.slack.security

import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * WebFlux WebFilter that verifies Slack request signatures.
 *
 * Only applies to /api/slack/ paths. Caches the request body
 * for signature verification and replays it for downstream handlers
 * via ServerHttpRequestDecorator.
 */
class SlackSignatureWebFilter(
    private val verifier: SlackSignatureVerifier,
    private val objectMapper: ObjectMapper
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()

        // Only filter Slack webhook paths
        if (!path.startsWith("/api/slack")) {
            return chain.filter(exchange)
        }

        val timestamp = exchange.request.headers.getFirst("X-Slack-Request-Timestamp")
        val signature = exchange.request.headers.getFirst("X-Slack-Signature")

        return DataBufferUtils.join(exchange.request.body)
            .flatMap { dataBuffer ->
                val bodyBytes = ByteArray(dataBuffer.readableByteCount())
                dataBuffer.read(bodyBytes)
                DataBufferUtils.release(dataBuffer)
                val body = String(bodyBytes, Charsets.UTF_8)

                val result = verifier.verify(timestamp, signature, body)
                if (!result.success) {
                    logger.warn { "Slack signature verification failed: ${result.errorMessage}" }
                    sendForbidden(exchange, result.errorMessage ?: "Signature verification failed")
                } else {
                    // Replay body for downstream handlers
                    val cachedBody = DefaultDataBufferFactory.sharedInstance
                        .wrap(bodyBytes)
                    val decoratedRequest = object : ServerHttpRequestDecorator(exchange.request) {
                        override fun getBody(): Flux<org.springframework.core.io.buffer.DataBuffer> {
                            return Flux.just(cachedBody)
                        }
                    }
                    val mutatedExchange = exchange.mutate().request(decoratedRequest).build()
                    chain.filter(mutatedExchange)
                }
            }
            .switchIfEmpty(chain.filter(exchange)) // Empty body = pass through (URL challenge)
    }

    private fun sendForbidden(exchange: ServerWebExchange, message: String): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.FORBIDDEN
        response.headers.contentType = MediaType.APPLICATION_JSON

        val errorBody = objectMapper.writeValueAsBytes(
            mapOf("error" to "Slack signature verification failed", "details" to message)
        )
        val buffer = response.bufferFactory().wrap(errorBody)
        return response.writeWith(Mono.just(buffer))
    }
}
