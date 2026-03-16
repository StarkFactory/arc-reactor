package com.arc.reactor.auth

import mu.KotlinLogging
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

private val logger = KotlinLogging.logger {}

/**
 * 기본 인증 제공자
 *
 * [UserStore]에 저장된 사용자 정보와 BCrypt 비밀번호 해싱을 사용하여 인증한다.
 * Spring Security Crypto의 [BCryptPasswordEncoder]를 사용하며,
 * Spring Security 전체를 의존하지 않는다.
 *
 * 기업 환경에서는 커스텀 [AuthProvider] 빈을 제공하여 이 구현체를 대체할 수 있다.
 *
 * @param userStore 사용자 저장소
 *
 * @see AuthProvider 인증 제공자 인터페이스
 * @see UserStore 사용자 저장소 인터페이스
 */
class DefaultAuthProvider(private val userStore: UserStore) : AuthProvider {

    private val passwordEncoder = BCryptPasswordEncoder()

    override fun authenticate(email: String, password: String): User? {
        val user = userStore.findByEmail(email) ?: return null
        return if (passwordEncoder.matches(password, user.passwordHash)) {
            logger.debug { "Authentication successful for: $email" }
            user
        } else {
            logger.debug { "Authentication failed for: $email" }
            null
        }
    }

    override fun getUserById(userId: String): User? = userStore.findById(userId)

    /**
     * 평문 비밀번호를 BCrypt로 해싱한다.
     * [AdminInitializer]에서 초기 관리자 계정 생성 시 사용한다.
     */
    fun hashPassword(rawPassword: String): String = passwordEncoder.encode(rawPassword)
}
