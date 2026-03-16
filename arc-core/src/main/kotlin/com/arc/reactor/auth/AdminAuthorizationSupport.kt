package com.arc.reactor.auth

import java.security.MessageDigest
import org.springframework.web.server.ServerWebExchange

/**
 * 관리자 인가 지원 유틸리티
 *
 * 웹/관리자 모듈에서 공유하는 관리자 인가 헬퍼이다.
 * 모든 관리자 인가 로직은 반드시 이 객체를 통해 수행해야 한다.
 * 인라인으로 중복 구현하면 안 된다.
 *
 * ## 정책
 * - [isAdmin]: 개발자 수준 관리자 접근을 확인한다 (ADMIN, ADMIN_DEVELOPER)
 * - [isAnyAdmin]: 매니저 또는 개발자 관리자 접근을 확인한다 (ADMIN, ADMIN_MANAGER, ADMIN_DEVELOPER)
 * - null 역할은 비관리자로 처리한다 (fail-close)
 *
 * ## 사용 규칙 (CLAUDE.md 필수)
 * 관리자 인증은 반드시 `AdminAuthSupport.isAdmin(exchange)` + `forbiddenResponse()`를 사용해야 하며,
 * 인라인으로 직접 구현하면 안 된다.
 *
 * @see JwtAuthWebFilter 인증 후 역할 정보를 exchange attribute에 저장
 * @see UserRole 사용자 역할 열거형
 */
object AdminAuthorizationSupport {

    /**
     * 개발자/관리자 컨트롤 서피스 접근 권한을 확인한다.
     * ADMIN 또는 ADMIN_DEVELOPER 역할일 때 true를 반환한다.
     */
    fun isAdmin(exchange: ServerWebExchange): Boolean {
        val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
        return role?.isDeveloperAdmin() == true
    }

    /**
     * 매니저 전용 대시보드를 포함한 광범위한 관리자 접근을 확인한다.
     * ADMIN, ADMIN_MANAGER, ADMIN_DEVELOPER 역할일 때 true를 반환한다.
     */
    fun isAnyAdmin(exchange: ServerWebExchange): Boolean {
        val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
        return role?.isAnyAdmin() == true
    }

    /**
     * 현재 요청의 actor(사용자 ID)를 반환한다.
     * 인증되지 않은 경우 "anonymous"를 반환한다.
     */
    fun currentActor(exchange: ServerWebExchange): String {
        return (exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "anonymous"
    }

    /**
     * 관리자 계정 참조를 SHA-256 해시로 마스킹하여 반환한다.
     * 감사 로그에 관리자 실제 ID 대신 해시 접두사를 기록하여 프라이버시를 보호한다.
     *
     * @param actor 관리자 식별자
     * @return "admin-account:{해시 앞 12자}" 형태의 마스킹된 참조
     */
    fun maskedAdminAccountRef(actor: String?): String {
        val normalized = actor?.trim()?.takeIf { it.isNotBlank() } ?: return "admin-account:unknown"
        if (normalized == "anonymous") return "admin-account:anonymous"
        return "admin-account:${sha256Hex(normalized).take(12)}"
    }

    /** SHA-256 해시를 16진수 문자열로 반환한다 */
    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
