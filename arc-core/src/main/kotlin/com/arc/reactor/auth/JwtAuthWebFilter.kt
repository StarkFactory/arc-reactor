package com.arc.reactor.auth

import mu.KotlinLogging
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JWT 인증 WebFilter
 *
 * 모든 수신 요청을 가로채어 Bearer 토큰을 검증한다.
 * 성공 시 userId, role, tenantId 등을 [ServerWebExchange.getAttributes]에 저장하여
 * 다운스트림 컨트롤러가 사용할 수 있게 한다.
 * 공개 경로(로그인, 등록)는 토큰 없이 통과시킨다.
 *
 * 이 필터는 Arc Reactor 런타임에서 항상 등록된다.
 *
 * ## JWT 인증 흐름 (각 단계 설명)
 * ```
 * 요청 수신
 *   → [1] 공개 경로 확인 → 공개면 바로 통과
 *   → [2] Authorization 헤더에서 Bearer 토큰 추출 → 없으면 401
 *   → [3] JWT 서명 검증 + userId(sub) 추출 → 유효하지 않으면 401
 *   → [4] 토큰 폐기 여부 확인 → 폐기되었으면 401
 *   → [5] userId → exchange attribute 저장
 *   → [6] 사용자 역할(role) 해석 → DB 우선, JWT 폴백 → 사용자 없으면 401
 *   → [7] tenantId 해석 → JWT claim 우선, 기본값 폴백
 *   → [8] email, accountId 해석 → exchange attribute 저장
 *   → [9] 다운스트림으로 체인 진행
 * ```
 *
 * ## 왜 DB에서 역할을 다시 조회하는가 (6단계)
 * JWT 토큰에 포함된 역할은 발급 시점의 값이다.
 * 관리자가 사용자 역할을 변경한 후에도 기존 토큰이 유효하므로,
 * DB에서 최신 역할을 조회하여 실시간 역할 변경을 반영한다.
 *
 * ## 왜 사용자 존재 여부를 확인하는가 (6단계)
 * authProvider가 있는데 해당 userId의 사용자가 DB에 없으면
 * 토큰이 유효하더라도 거부한다. 삭제된 사용자의 토큰이
 * 여전히 작동하는 것을 방지하기 위함이다.
 *
 * @param jwtTokenProvider JWT 토큰 검증/파싱 제공자
 * @param authProperties 인증 설정 (공개 경로, 기본 tenantId 등)
 * @param authProvider 사용자 조회 제공자 (선택사항, null이면 JWT만으로 인증)
 * @param tokenRevocationStore 토큰 폐기 저장소 (선택사항)
 *
 * @see JwtTokenProvider JWT 토큰 생성/검증
 * @see AuthProperties 인증 설정
 * @see AdminAuthorizationSupport 관리자 인가 헬퍼
 */
class JwtAuthWebFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val authProperties: AuthProperties,
    private val authProvider: AuthProvider? = null,
    private val tokenRevocationStore: TokenRevocationStore? = null
) : WebFilter, Ordered {

    /** tenantId 값 유효성 검증용 정규식 */
    private val tenantPattern = Regex("^[a-zA-Z0-9_-]{1,64}$")

    /** AuthRateLimitFilter(HIGHEST+1) 바로 다음에 실행 */
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 2

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.uri.path

        // ── [1] 공개 경로 확인: 인증 없이 통과 ──
        if (isPublicPath(path)) {
            return chain.filter(exchange)
        }

        // ── [2] Bearer 토큰 추출 ──
        val authHeader = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange)
        }

        // ── [3] JWT 서명 검증 + userId 추출 ──
        val token = authHeader.substring(7)
        val userId = jwtTokenProvider.validateToken(token)
        if (userId == null) {
            return unauthorized(exchange)
        }

        // ── [4] 토큰 폐기 여부 확인 ──
        if (isRevoked(token)) {
            return unauthorized(exchange)
        }

        // ── [5] userId를 exchange attribute에 저장 ──
        // 빈 userId(sub claim)는 변조된 토큰 — anonymous 폴백 대신 거부
        if (userId.isBlank()) {
            logger.warn { "빈 sub claim 토큰 거부" }
            return unauthorized(exchange)
        }
        exchange.attributes[USER_ID_ATTRIBUTE] = userId

        // ── [6] 사용자 역할 해석 (DB 우선, JWT 폴백) ──
        val role = resolveUserRole(userId, token) ?: return unauthorized(exchange)
        exchange.attributes[USER_ROLE_ATTRIBUTE] = role

        // ── [7] tenantId 해석 ──
        val tenantId = resolveTenantId(token)
        exchange.attributes[RESOLVED_TENANT_ID_ATTRIBUTE] = tenantId

        // ── [8] email, accountId 해석 ──
        exchange.attributes[USER_EMAIL_ATTRIBUTE] = resolveUserEmail(userId, token) ?: "anonymous"
        exchange.attributes[USER_ACCOUNT_ID_ATTRIBUTE] = resolveUserAccountId(token, userId)

        // ── [9] 다운스트림으로 체인 진행 ──
        return chain.filter(exchange)
    }

    /** JWT 또는 폴백에서 accountId를 해석한다 */
    private fun resolveUserAccountId(token: String, userId: String): String {
        return jwtTokenProvider.extractAccountId(token)
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: userId
    }

    /**
     * JWT claim 또는 DB에서 이메일을 해석한다.
     *
     * JWT email claim은 외부 입력이므로 형식 검증 + 길이 제한을 적용한다.
     * 미검증 시 SQL 인젝션, 로그 인젝션, 감사 로그 오염 경로가 열린다.
     */
    private fun resolveUserEmail(userId: String, token: String): String? {
        // JWT claim 우선
        val tokenEmail = jwtTokenProvider.extractEmail(token)
            ?.trim()
            ?.takeIf { it.isNotBlank() && isValidEmailFormat(it) }
        if (!tokenEmail.isNullOrBlank()) return tokenEmail

        // DB 폴백
        val resolvedUser = authProvider?.getUserById(userId)
        return resolvedUser?.email?.takeIf { it.isNotBlank() }
    }

    /**
     * 사용자 역할을 해석한다.
     * DB에서 최신 역할을 우선 조회하고, 없으면 JWT claim의 역할을 사용한다.
     * authProvider가 있는데 사용자가 DB에 없으면 null을 반환하여 인증을 거부한다.
     */
    private fun resolveUserRole(userId: String, token: String): UserRole? {
        val tokenRole = jwtTokenProvider.extractRole(token) ?: UserRole.USER
        val resolvedUser = authProvider?.getUserById(userId)
        // authProvider가 있는데 사용자를 못 찾으면 → 삭제된 사용자 → 거부
        if (authProvider != null && resolvedUser == null) {
            logger.warn { "존재하지 않는 사용자 토큰 거부: userId=$userId" }
            return null
        }
        return resolvedUser?.role ?: tokenRole
    }

    /**
     * 토큰의 jti(토큰 ID)가 폐기되었는지 확인한다.
     * 폐기 저장소가 구성된 상태에서 jti가 없는 토큰은 폐기 불가능하므로 거부한다.
     *
     * R323 fix: 저장소 조회 중 발생한 예외를 catch하여 **fail-closed**(폐기된 것으로 간주)로
     * 처리한다. 기존 구현은 `JdbcTokenRevocationStore.isRevoked`/`RedisTokenRevocationStore.isRevoked`
     * 에서 발생한 `DataAccessException`/`RedisConnectionFailureException`이 필터 체인까지 전파되어
     * 모든 인증 요청이 HTTP 500으로 실패했다. DB/Redis 일시 장애 시 인증은 동작해야 하지만
     * 동시에 폐기 확인이 불가능한 요청을 허용하면 이미 폐기된 토큰이 통과할 수 있다. 운영 측면
     * 가용성보다 보안 무결성을 우선하여 fail-closed(거부)로 전환한다. 경고 로그로 백엔드 장애를
     * 즉시 인지 가능.
     */
    private fun isRevoked(token: String): Boolean {
        val tokenId = jwtTokenProvider.extractTokenId(token)
        if (tokenId == null) {
            // 폐기 저장소가 있는데 jti가 없으면 → 폐기 불가능한 토큰 → 거부
            if (tokenRevocationStore != null) {
                logger.warn { "jti 없는 토큰 거부 — 폐기 저장소 활성 상태" }
                return true
            }
            return false
        }
        val store = tokenRevocationStore ?: return false
        val revoked = try {
            store.isRevoked(tokenId)
        } catch (e: Exception) {
            // CancellationException이면 즉시 전파 (suspend 컨텍스트가 아니지만 방어적으로 유지)
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.warn(e) {
                "토큰 폐기 저장소 조회 실패 — fail-closed로 거부: jti=$tokenId, cause=${e.javaClass.simpleName}"
            }
            true
        }
        if (revoked) {
            logger.warn { "폐기된 토큰 거부: jti=$tokenId" }
        }
        return revoked
    }

    /**
     * JWT claim에서 tenantId를 추출하여 유효성을 검증한다.
     * 유효하지 않으면 기본 tenantId를 반환한다.
     */
    private fun resolveTenantId(token: String): String {
        val tokenTenant = jwtTokenProvider.extractTenantId(token)
            ?.takeIf { tenantPattern.matches(it) }
        return tokenTenant ?: authProperties.defaultTenantId
    }

    /** 공개 경로 여부를 확인한다 */
    private fun isPublicPath(path: String): Boolean {
        return authProperties.publicPaths.any { path.startsWith(it) }
    }

    /** 401 Unauthorized 응답을 JSON 본문과 함께 반환한다. */
    private fun unauthorized(exchange: ServerWebExchange): Mono<Void> {
        logger.debug { "인증되지 않은 요청: ${exchange.request.method} ${exchange.request.uri.path}" }
        val response = exchange.response
        response.statusCode = HttpStatus.UNAUTHORIZED
        response.headers.contentType = MediaType.APPLICATION_JSON
        val body = """{"error":"Unauthorized","details":null,"timestamp":"${Instant.now()}"}"""
        val buffer = DefaultDataBufferFactory.sharedInstance.wrap(body.toByteArray(Charsets.UTF_8))
        return response.writeWith(Mono.just(buffer))
    }

    companion object {
        /** 최대 이메일 길이. RFC 5321 최대 254자이나 실용적으로 128자로 제한. */
        private const val MAX_EMAIL_LENGTH = 128

        /** 기본적인 이메일 형식 검증 (local@domain). RFC 5321 완전 준수는 아님. */
        private val EMAIL_PATTERN = Regex("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$")

        /** JWT email claim의 형식과 길이를 검증한다. 제어 문자, 극단적 길이를 차단. */
        private fun isValidEmailFormat(email: String): Boolean {
            if (email.length > MAX_EMAIL_LENGTH) return false
            if (email.any { it.isISOControl() }) return false
            return EMAIL_PATTERN.matches(email)
        }

        /** 인증된 userId를 ServerWebExchange attribute에 저장하는 키 */
        const val USER_ID_ATTRIBUTE = "userId"

        /** 인증된 사용자의 [UserRole]을 ServerWebExchange attribute에 저장하는 키 */
        const val USER_ROLE_ATTRIBUTE = "userRole"

        /** 해석된 tenant ID를 ServerWebExchange attribute에 저장하는 키 */
        const val RESOLVED_TENANT_ID_ATTRIBUTE = "resolvedTenantId"

        /** 해석된 사용자 이메일을 ServerWebExchange attribute에 저장하는 키 */
        const val USER_EMAIL_ATTRIBUTE = "userEmail"

        /** 해석된 사용자 accountId를 ServerWebExchange attribute에 저장하는 키 */
        const val USER_ACCOUNT_ID_ATTRIBUTE = "userAccountId"
    }
}
