package com.arc.reactor.slack.security

import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebExchangeDecorator
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URLDecoder
import java.time.Instant

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
) : WebFilter, Ordered {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()

        // Only filter Slack webhook paths
        if (!path.startsWith("/api/slack")) {
            return chain.filter(exchange)
        }

        val timestamp = exchange.request.headers.getFirst("X-Slack-Request-Timestamp")
        val signature = exchange.request.headers.getFirst("X-Slack-Signature")

        return DataBufferUtils.join(exchange.request.body)
            .map { dataBuffer ->
                val bodyBytes = ByteArray(dataBuffer.readableByteCount())
                dataBuffer.read(bodyBytes)
                DataBufferUtils.release(dataBuffer)
                bodyBytes
            }
            .defaultIfEmpty(ByteArray(0))
            .flatMap { bodyBytes ->
                verifyAndFilter(exchange, chain, timestamp, signature, bodyBytes)
            }
    }

    // Must run before form/body parsing filters so raw body is still available for signature verification.
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    private fun verifyAndFilter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
        timestamp: String?,
        signature: String?,
        bodyBytes: ByteArray
    ): Mono<Void> {
        val body = String(bodyBytes, Charsets.UTF_8)
        val result = verifier.verify(timestamp, signature, body)
        if (!result.success) {
            logger.warn { "Slack signature verification failed: ${result.errorMessage}" }
            return sendForbidden(exchange, result.errorMessage ?: "Signature verification failed")
        }
        if (bodyBytes.isEmpty()) {
            return chain.filter(exchange)
        }
        val isFormUrlEncoded = exchange.request.headers.contentType
            ?.isCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED) == true
        val formParameters = if (isFormUrlEncoded) parseFormParameters(body) else null
        val decoratedRequest = object : ServerHttpRequestDecorator(exchange.request) {
            override fun getBody(): Flux<DataBuffer> = Flux.defer {
                Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bodyBytes))
            }
        }
        val mutatedExchange = exchange.mutate().request(decoratedRequest).build()
        if (!isFormUrlEncoded || formParameters == null) {
            return chain.filter(mutatedExchange)
        }
        val formAwareExchange = object : ServerWebExchangeDecorator(mutatedExchange) {
            override fun getFormData(): Mono<MultiValueMap<String, String>> =
                Mono.just(copyParameters(formParameters))
        }
        return chain.filter(formAwareExchange)
    }

    private fun parseFormParameters(body: String): MultiValueMap<String, String> {
        val params = LinkedMultiValueMap<String, String>()
        if (body.isBlank()) return params
        body.split("&")
            .filter { it.isNotBlank() }
            .forEach { pair ->
                val separatorIndex = pair.indexOf('=')
                val rawKey = if (separatorIndex >= 0) pair.substring(0, separatorIndex) else pair
                val rawValue = if (separatorIndex >= 0) pair.substring(separatorIndex + 1) else ""
                val key = URLDecoder.decode(rawKey, Charsets.UTF_8)
                val value = URLDecoder.decode(rawValue, Charsets.UTF_8)
                params.add(key, value)
            }
        return params
    }

    private fun copyParameters(source: MultiValueMap<String, String>): MultiValueMap<String, String> {
        val copied = LinkedMultiValueMap<String, String>(source.size)
        source.forEach { (key, values) -> copied.put(key, values.toMutableList()) }
        return copied
    }

    private fun sendForbidden(exchange: ServerWebExchange, message: String): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.FORBIDDEN
        response.headers.contentType = MediaType.APPLICATION_JSON

        val errorBody = objectMapper.writeValueAsBytes(
            SignatureErrorResponse(
                error = "Slack signature verification failed",
                details = message,
                timestamp = Instant.now().toString()
            )
        )
        val buffer = response.bufferFactory().wrap(errorBody)
        return response.writeWith(Mono.just(buffer))
    }
}

private data class SignatureErrorResponse(
    val error: String,
    val details: String,
    val timestamp: String
)
