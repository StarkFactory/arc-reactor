package com.arc.reactor.autoconfigure

import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * 요청 상관 ID WebFilter.
 *
 * 모든 HTTP 요청에 대해 고유한 상관 ID를 전파하거나 생성합니다:
 * - 수신된 `X-Request-ID` 헤더 값이 있으면 사용한다.
 * - 헤더가 없으면 랜덤 UUID를 생성한다.
 * - 클라이언트가 서버 로그와 대조할 수 있도록 응답에 `X-Request-ID`를 설정한다.
 * - 하위 MDC 전파를 위해 Reactor 컨텍스트에 `requestId`를 기록한다.
 *
 * 기본값: 활성화. `arc.reactor.request-correlation.enabled=false`로 비활성화.
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
