package com.arc.reactor.auth

import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * 서버 시작 시 초기 ADMIN 계정을 생성하는 초기화기
 *
 * 환경 변수에서 관리자 정보를 읽어 DB에 계정이 없으면 생성한다.
 *
 * ## 필수 환경 변수
 * - `ARC_REACTOR_AUTH_ADMIN_EMAIL`: 관리자 이메일
 * - `ARC_REACTOR_AUTH_ADMIN_PASSWORD`: 관리자 비밀번호 (최소 8자)
 *
 * ## 선택 환경 변수
 * - `ARC_REACTOR_AUTH_ADMIN_NAME`: 표시 이름 (기본값: "Admin")
 *
 * ## 동작 규칙
 * - 해당 이메일이 이미 DB에 존재하면 아무것도 하지 않는다
 * - 환경 변수가 설정되지 않으면 조용히 건너뛴다
 * - 비밀번호가 8자 미만이면 경고 후 건너뛴다
 * - [DefaultAuthProvider]가 아닌 커스텀 AuthProvider를 사용하면 비밀번호 해싱이 불가하므로 건너뛴다
 *
 * @param userStore 사용자 저장소
 * @param authProvider 인증 제공자 (비밀번호 해싱용)
 * @param envReader 환경 변수 읽기 함수 (테스트 주입 가능)
 *
 * @see DefaultAuthProvider 비밀번호 해싱을 제공하는 기본 인증 제공자
 */
class AdminInitializer(
    private val userStore: UserStore,
    private val authProvider: AuthProvider,
    private val envReader: (String) -> String? = System::getenv
) {

    @EventListener(ApplicationReadyEvent::class)
    fun initAdmin() {
        // ── 단계 1: 환경 변수 읽기 ──
        val email = envReader("ARC_REACTOR_AUTH_ADMIN_EMAIL")
        val password = envReader("ARC_REACTOR_AUTH_ADMIN_PASSWORD")

        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            logger.debug { "Admin env vars not set, skipping admin initialization" }
            return
        }

        // ── 단계 2: 비밀번호 길이 검증 ──
        if (password.length < 8) {
            logger.warn { "Admin password must be at least 8 characters, skipping admin initialization" }
            return
        }

        // ── 단계 3: 이미 존재하는지 확인 ──
        if (userStore.existsByEmail(email)) {
            logger.info { "Admin account already exists: $email" }
            return
        }

        // ── 단계 4: 비밀번호 해싱 ──
        val name = envReader("ARC_REACTOR_AUTH_ADMIN_NAME").takeUnless { it.isNullOrBlank() } ?: "Admin"

        val passwordHash = when (authProvider) {
            is DefaultAuthProvider -> authProvider.hashPassword(password)
            else -> {
                logger.warn { "Custom AuthProvider — cannot hash password for admin seed" }
                return
            }
        }

        // ── 단계 5: ADMIN 계정 생성 ──
        val admin = User(
            id = UUID.randomUUID().toString(),
            email = email,
            name = name,
            passwordHash = passwordHash,
            role = UserRole.ADMIN
        )
        userStore.save(admin)
        logger.info { "Initial ADMIN account created: $email" }
    }
}
