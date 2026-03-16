package com.arc.reactor.auth

/**
 * 인증 제공자 인터페이스
 *
 * 사용자 인증 로직을 추상화한다. 기업 환경에서는 커스텀 구현체
 * (예: LDAP, SSO, OAuth2)로 기본 빈을 대체할 수 있다.
 *
 * @see DefaultAuthProvider BCrypt 기반 기본 구현체
 * @see UserStore 사용자 저장소 인터페이스
 */
interface AuthProvider {

    /**
     * 이메일과 비밀번호로 사용자를 인증한다.
     *
     * @return 자격 증명이 유효한 경우 인증된 [User], 아니면 null
     */
    fun authenticate(email: String, password: String): User?

    /**
     * ID로 사용자를 조회한다.
     *
     * @return 찾은 경우 [User], 아니면 null
     */
    fun getUserById(userId: String): User?
}
