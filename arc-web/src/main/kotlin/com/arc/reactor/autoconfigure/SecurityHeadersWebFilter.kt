package com.arc.reactor.autoconfigure

import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * 보안 헤더 WebFilter.
 *
 * HTTP 응답에 표준 보안 헤더를 추가합니다:
 * - `X-Content-Type-Options: nosniff` -- MIME 타입 스니핑 방지
 * - `X-Frame-Options: DENY` -- 클릭재킹 방지
 * - `Content-Security-Policy: default-src 'self'` -- 리소스 로딩 제한 (API 경로 전용)
 * - `X-XSS-Protection: 0` -- 레거시 XSS 필터 비활성화 (최신 권장 사항)
 * - `Referrer-Policy: strict-origin-when-cross-origin` -- referrer 정보 제한
 * - `Strict-Transport-Security: max-age=31536000; includeSubDomains` -- HTTPS 강제
 *
 * Swagger UI 경로(`/swagger-ui`, `/v3/api-docs`, `/webjars`)에는 UI에 필요한
 * 인라인 스타일을 허용하는 완화된 CSP를 적용합니다.
 *
 * 기본값: 활성화. `arc.reactor.security-headers.enabled=false`로 비활성화.
 */
class SecurityHeadersWebFilter : WebFilter, Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        val csp = if (isSwaggerPath(path)) {
            "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'"
        } else {
            "default-src 'self'"
        }
        exchange.response.headers.apply {
            set("X-Content-Type-Options", "nosniff")
            set("X-Frame-Options", "DENY")
            set("Content-Security-Policy", csp)
            set("X-XSS-Protection", "0")
            set("Referrer-Policy", "strict-origin-when-cross-origin")
            set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
        return chain.filter(exchange)
    }

    private fun isSwaggerPath(path: String): Boolean =
        path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs") || path.startsWith("/webjars")
}
