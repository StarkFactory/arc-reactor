package com.arc.reactor.auth

import java.time.Instant

/**
 * 접근 제어용 사용자 역할 열거형
 *
 * - [ADMIN]: 모든 관리 영역에 접근 가능 (레거시 전체 접근 역할)
 * - [ADMIN_DEVELOPER]: 개발자/관리자 컨트롤 서피스에 접근 가능
 * - [ADMIN_MANAGER]: 매니저 대시보드에 접근 가능
 * - [USER]: 표준 접근 (채팅, 페르소나 선택)
 *
 * @see AdminAuthorizationSupport 역할 기반 인가 헬퍼
 */
enum class UserRole {
    USER,
    ADMIN,
    ADMIN_MANAGER,
    ADMIN_DEVELOPER;

    /** 매니저 또는 개발자 관리자인지 확인한다 */
    fun isAnyAdmin(): Boolean = this == ADMIN || this == ADMIN_MANAGER || this == ADMIN_DEVELOPER

    /** 개발자 수준 관리자인지 확인한다 */
    fun isDeveloperAdmin(): Boolean = this == ADMIN || this == ADMIN_DEVELOPER

    /** 프론트엔드 작업 공간 결정을 위한 관리자 범위를 반환한다 */
    fun adminScope(): AdminScope? = when (this) {
        ADMIN -> AdminScope.FULL
        ADMIN_MANAGER -> AdminScope.MANAGER
        ADMIN_DEVELOPER -> AdminScope.DEVELOPER
        USER -> null
    }
}

/**
 * 프론트엔드 작업 공간 결정을 위한 대략적 관리자 범위
 */
enum class AdminScope {
    /** 전체 접근 (ADMIN 역할) */
    FULL,
    /** 매니저 범위 (ADMIN_MANAGER 역할) */
    MANAGER,
    /** 개발자 범위 (ADMIN_DEVELOPER 역할) */
    DEVELOPER
}

/**
 * 폐기된 JWT 토큰 영속화 백엔드 유형
 */
enum class TokenRevocationStoreType {
    /** 메모리 기반 (단일 인스턴스용) */
    MEMORY,
    /** JDBC 기반 (다중 인스턴스용) */
    JDBC,
    /** Redis 기반 (다중 인스턴스용, 자동 만료 지원) */
    REDIS
}

/**
 * 인증된 사용자 데이터 클래스
 *
 * @param id 고유 식별자 (UUID)
 * @param email 사용자 이메일 (고유, 로그인에 사용)
 * @param name 표시 이름
 * @param passwordHash BCrypt 해시된 비밀번호
 * @param role 접근 제어용 사용자 역할 (기본값: USER)
 * @param createdAt 계정 생성 시각
 */
data class User(
    val id: String,
    val email: String,
    val name: String,
    val passwordHash: String,
    val role: UserRole = UserRole.USER,
    val createdAt: Instant = Instant.now()
)

/**
 * 인증 설정 속성 (접두사: arc.reactor.auth)
 *
 * 인증은 Arc Reactor 런타임에서 필수이다.
 *
 * @param jwtSecret JWT 서명용 HMAC 시크릿.
 *   최소 32자 이상이어야 한다. 생성 명령: `openssl rand -base64 32`
 * @param jwtExpirationMs 토큰 유효 기간 (밀리초, 기본값: 24시간)
 * @param defaultTenantId 테넌트 컨텍스트가 없을 때 JWT에 포함할 기본 테넌트 ID
 * @param selfRegistrationEnabled `/api/auth/register`의 공개 사용 가능 여부
 * @param publicPaths 인증을 우회하는 URL 접두사 목록
 * @param loginRateLimitPerMinute IP당 분당 최대 인증 시도 횟수 (무차별 대입 방지)
 * @param trustForwardedHeaders 클라이언트 IP 추출 시 X-Forwarded-For 헤더 신뢰 여부. 신뢰할 수 있는 리버스 프록시 뒤에서만 활성화
 * @param tokenRevocationStore 폐기된 JWT 토큰 ID 추적에 사용할 백엔드
 */
data class AuthProperties(
    val jwtSecret: String = "",
    val jwtExpirationMs: Long = 86_400_000,
    val defaultTenantId: String = "default",
    val selfRegistrationEnabled: Boolean = false,
    val publicPaths: List<String> = listOf(
        "/api/auth/login",
        "/actuator/health",
        "/v3/api-docs", "/swagger-ui", "/webjars"
    ),
    /** IP당 분당 최대 인증 시도 횟수 (무차별 대입 방지) */
    val loginRateLimitPerMinute: Int = 10,
    /** X-Forwarded-For 헤더 신뢰 여부. 신뢰할 수 있는 리버스 프록시 뒤에서만 활성화 */
    val trustForwardedHeaders: Boolean = false,
    val tokenRevocationStore: TokenRevocationStoreType = TokenRevocationStoreType.MEMORY
)
