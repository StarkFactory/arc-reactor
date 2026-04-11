package com.arc.reactor.auth

import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * 인증 엔드포인트 무차별 대입 방지 속도 제한 필터
 *
 * 로그인/등록 시도를 IP 주소당 분당 횟수로 제한하여
 * 무차별 대입(brute-force) 공격을 방지한다.
 * 한도 초과 시 HTTP 429 (Too Many Requests)를 반환한다.
 *
 * ## 왜 별도의 속도 제한 필터인가
 * Guard의 [com.arc.reactor.guard.impl.DefaultRateLimitStage]는 인증된 사용자의
 * 에이전트 요청을 제한한다. 이 필터는 인증 **전** 단계에서 로그인/등록 시도를
 * IP 기반으로 제한하여 무차별 대입 공격을 차단한다.
 *
 * ## 동작 방식
 * 1. POST /api/auth/login 또는 /api/auth/register 요청만 대상
 * 2. 클라이언트 IP + 경로 조합으로 캐시 키 생성
 * 3. 실패 횟수가 한도에 도달하면 이후 요청을 1분간 차단
 * 4. 성공 시(2xx) 실패 카운터를 초기화
 *
 * 인증은 런타임 필수이므로 이 필터는 항상 활성화된다.
 *
 * @param maxAttemptsPerMinute IP당 분당 최대 시도 횟수 (기본값: 10)
 * @param trustForwardedHeaders X-Forwarded-For 헤더 신뢰 여부. 신뢰할 수 있는 리버스 프록시 뒤에서만 활성화
 *
 * @see JwtAuthWebFilter JWT 인증 필터 (이 필터 이후 실행)
 */
class AuthRateLimitFilter(
    private val maxAttemptsPerMinute: Int = 10,
    private val trustForwardedHeaders: Boolean = false
) : WebFilter, Ordered {

    /** IP:경로 → 실패 횟수 캐시 (1분 TTL) */
    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .maximumSize(10_000)
        .build<String, AtomicInteger>()

    /** JwtAuthWebFilter(HIGHEST+2)보다 먼저 실행 */
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        val method = exchange.request.method

        // 인증 관련 POST 요청만 속도 제한 대상
        if (!isRateLimitedAuthPath(path, method.name())) {
            return chain.filter(exchange)
        }

        val key = rateLimitKey(exchange, path)
        if (isBlocked(key)) {
            return tooManyRequests(exchange)
        }

        return chain.filter(exchange)
            .doOnSuccess { handleCompletedAttempt(exchange, key) }
            .doOnError { recordFailure(key) }
    }

    /** 클라이언트 IP를 추출한다. trustForwardedHeaders가 true이면 X-Forwarded-For를 우선 사용한다. */
    private fun extractClientIp(exchange: ServerWebExchange): String {
        if (trustForwardedHeaders) {
            val forwarded = exchange.request.headers.getFirst("X-Forwarded-For")
            if (forwarded != null) {
                return forwarded.split(",").first().trim()
            }
        }
        return exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
    }

    /** 속도 제한 캐시 키를 생성한다 (IP:경로) */
    private fun rateLimitKey(exchange: ServerWebExchange, path: String): String {
        return "${extractClientIp(exchange)}:$path"
    }

    /** 실패 횟수가 한도에 도달했는지 확인한다 */
    private fun isBlocked(key: String): Boolean {
        val failures = cache.getIfPresent(key)?.get() ?: 0
        return failures >= maxAttemptsPerMinute
    }

    /**
     * 요청 완료 후 결과에 따라 카운터를 처리한다.
     *
     * R325 fix: 기존 구현은 `status == null || status.is2xxSuccessful`로 null 상태를 성공으로
     * 취급하여 `cache.invalidate(key)`를 호출했다. Spring WebFlux의 `doOnSuccess` 콜백은 응답
     * 커밋 전에 발생할 수 있고, 그 시점에 `exchange.response.statusCode`가 아직 설정되지
     * 않은 경우 null이 반환된다. **공격자가 null status 응답을 유도할 수 있다면 실패 카운터가
     * 리셋되어 brute-force 한도를 우회할 수 있다**. null 상태는 "확정 불가"로 간주하여 카운터를
     * 그대로 두고, **명시적 2xx만** 카운터를 리셋한다. 4xx/5xx는 기존대로 실패로 기록.
     */
    private fun handleCompletedAttempt(exchange: ServerWebExchange, key: String) {
        val status = exchange.response.statusCode
        if (status == null) {
            // 상태 코드가 확정되지 않은 경우 카운터를 건드리지 않는다 (conservative):
            // null로 실패 카운터를 리셋하면 brute-force 우회 경로가 된다.
            return
        }
        if (status.is2xxSuccessful) {
            // 성공 시 카운터 초기화 — 정상 사용자의 카운터를 즉시 해제
            cache.invalidate(key)
            return
        }
        if (status.value() >= 400) {
            // 4xx/5xx 응답은 실패로 기록
            recordFailure(key)
        }
    }

    /** 실패 횟수를 증가시킨다 */
    private fun recordFailure(key: String) {
        val count = cache.get(key) { AtomicInteger(0) }.incrementAndGet()
        if (count == maxAttemptsPerMinute) {
            logger.warn {
                "Auth failure limit reached for key=$key ($count/$maxAttemptsPerMinute/min); " +
                    "subsequent attempts will be blocked for 1 minute"
            }
        }
    }

    /** HTTP 429 응답을 반환한다 */
    private fun tooManyRequests(exchange: ServerWebExchange): Mono<Void> {
        exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        exchange.response.headers.set("Retry-After", "60")
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val body = """{"error":"Too many authentication attempts. Please try again later.","details":null,"timestamp":"${java.time.Instant.now()}"}"""
        val buffer = exchange.response.bufferFactory().wrap(body.toByteArray())
        return exchange.response.writeWith(Mono.just(buffer))
    }

    /** 속도 제한 대상 인증 경로인지 확인한다 (POST /api/auth/login, /api/auth/register) */
    private fun isRateLimitedAuthPath(path: String, method: String?): Boolean {
        if (method != "POST") {
            return false
        }
        return path == "/api/auth/login" || path == "/api/auth/register"
    }
}
