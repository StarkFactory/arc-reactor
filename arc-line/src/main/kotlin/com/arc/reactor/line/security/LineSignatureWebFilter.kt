package com.arc.reactor.line.security

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
 * WebFlux WebFilter that verifies LINE webhook request signatures.
 *
 * Only applies to /api/line/ paths. Caches the request body
 * for signature verification and replays it for downstream handlers
 * via ServerHttpRequestDecorator.
 */
class LineSignatureWebFilter(
    private val verifier: LineSignatureVerifier,
    private val objectMapper: ObjectMapper
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()

        // Only filter LINE webhook paths
        if (!path.startsWith("/api/line")) {
            return chain.filter(exchange)
        }

        val signature = exchange.request.headers.getFirst("x-line-signature")

        return DataBufferUtils.join(exchange.request.body)
            .flatMap { dataBuffer ->
                val bodyBytes = ByteArray(dataBuffer.readableByteCount())
                dataBuffer.read(bodyBytes)
                DataBufferUtils.release(dataBuffer)
                val body = String(bodyBytes, Charsets.UTF_8)

                val valid = verifier.verify(body, signature)
                if (!valid) {
                    logger.warn { "LINE signature verification failed" }
                    sendForbidden(exchange, "Signature verification failed")
                } else {
                    // Replay body for downstream handlers
                    val cachedBody = DefaultDataBufferFactory.sharedInstance
                        .wrap(bodyBytes)
                    val decoratedRequest =
                        object : ServerHttpRequestDecorator(exchange.request) {
                            override fun getBody():
                                Flux<org.springframework.core.io.buffer.DataBuffer> {
                                return Flux.just(cachedBody)
                            }
                        }
                    val mutatedExchange = exchange.mutate()
                        .request(decoratedRequest).build()
                    chain.filter(mutatedExchange)
                }
            }
            .switchIfEmpty(chain.filter(exchange))
    }

    private fun sendForbidden(
        exchange: ServerWebExchange,
        message: String
    ): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.FORBIDDEN
        response.headers.contentType = MediaType.APPLICATION_JSON

        val errorBody = objectMapper.writeValueAsBytes(
            mapOf(
                "error" to "LINE signature verification failed",
                "details" to message
            )
        )
        val buffer = response.bufferFactory().wrap(errorBody)
        return response.writeWith(Mono.just(buffer))
    }
}
