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
 * Slack 요청 서명을 검증하는 WebFlux WebFilter.
 *
 * `/api/slack/` 경로에만 적용된다. 서명 검증을 위해 요청 본문을 캐싱하고,
 * 다운스트림 핸들러에게 ServerHttpRequestDecorator를 통해 본문을 재생(replay)한다.
 * form-urlencoded 요청의 경우 파싱된 폼 파라미터도 함께 제공한다.
 *
 * @param verifier 서명 검증기
 * @param objectMapper JSON 직렬화용 ObjectMapper (에러 응답 생성)
 * @see SlackSignatureVerifier
 */
class SlackSignatureWebFilter(
    private val verifier: SlackSignatureVerifier,
    private val objectMapper: ObjectMapper
) : WebFilter, Ordered {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()

        // Slack 웹훅 경로만 필터링
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

    // 서명 검증을 위해 원시 본문이 필요하므로, 폼/본문 파싱 필터보다 먼저 실행되어야 한다.
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
            logger.warn { "Slack 서명 검증 실패: reason=${result.errorMessage}" }
            return sendForbidden(exchange, result.errorMessage ?: "Signature verification failed")
        }
        if (bodyBytes.isEmpty()) {
            return chain.filter(exchange)
        }
        val decoratedExchange = decorateWithCachedBody(exchange, bodyBytes, body)
        return chain.filter(decoratedExchange)
    }

    /** 캐싱된 본문을 재생하는 데코레이터를 생성한다. form-urlencoded이면 폼 파라미터도 포함한다. */
    private fun decorateWithCachedBody(
        exchange: ServerWebExchange,
        bodyBytes: ByteArray,
        body: String
    ): ServerWebExchange {
        val isFormUrlEncoded = exchange.request.headers.contentType
            ?.isCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED) == true
        val formParameters = if (isFormUrlEncoded) parseFormParameters(body) else null
        val decoratedRequest = object : ServerHttpRequestDecorator(exchange.request) {
            override fun getBody(): Flux<DataBuffer> = Flux.defer {
                Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bodyBytes))
            }
        }
        val mutatedExchange = exchange.mutate().request(decoratedRequest).build()
        if (!isFormUrlEncoded || formParameters == null) return mutatedExchange
        return object : ServerWebExchangeDecorator(mutatedExchange) {
            override fun getFormData(): Mono<MultiValueMap<String, String>> =
                Mono.just(copyParameters(formParameters))
        }
    }

    private fun parseFormParameters(body: String): MultiValueMap<String, String> {
        val params = LinkedMultiValueMap<String, String>()
        if (body.isBlank()) return params
        val pairs = body.split("&").filter { it.isNotBlank() }
        for (pair in pairs) {
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
        for ((key, values) in source) {
            copied.put(key, values.toMutableList())
        }
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

/** 서명 검증 실패 시 403 응답 본문. */
private data class SignatureErrorResponse(
    val error: String,
    val details: String,
    val timestamp: String
)
