package com.arc.reactor.auth

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.server.ServerWebExchange

/**
 * AdminAuthorizationSupport 단위 테스트.
 *
 * isAdmin, isAnyAdmin, currentActor, maskedAdminAccountRef 의 모든 경계 조건을 검증한다.
 */
class AdminAuthorizationSupportTest {

    private fun exchangeWithRole(role: UserRole?): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attrs = mutableMapOf<String, Any>()
        if (role != null) {
            attrs[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] = role
        }
        every { exchange.attributes } returns attrs
        return exchange
    }

    private fun exchangeWithUserId(userId: String?): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attrs = mutableMapOf<String, Any>()
        if (userId != null) {
            attrs[JwtAuthWebFilter.USER_ID_ATTRIBUTE] = userId
        }
        every { exchange.attributes } returns attrs
        return exchange
    }

    // ─── isAdmin ──────────────────────────────────────────────────────────────

    @Nested
    inner class IsAdmin {

        @Test
        fun `ADMIN 역할은 isAdmin이 true를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.isAdmin(exchangeWithRole(UserRole.ADMIN))
            assertTrue(result) { "ADMIN 역할은 개발자 관리자 접근을 허용해야 한다" }
        }

        @Test
        fun `ADMIN_DEVELOPER 역할은 isAdmin이 true를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.isAdmin(exchangeWithRole(UserRole.ADMIN_DEVELOPER))
            assertTrue(result) { "ADMIN_DEVELOPER 역할은 개발자 관리자 접근을 허용해야 한다" }
        }

        @Test
        fun `ADMIN_MANAGER 역할은 isAdmin이 false를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.isAdmin(exchangeWithRole(UserRole.ADMIN_MANAGER))
            assertFalse(result) { "ADMIN_MANAGER 역할은 개발자 컨트롤 서피스에 접근할 수 없어야 한다" }
        }

        @Test
        fun `USER 역할은 isAdmin이 false를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.isAdmin(exchangeWithRole(UserRole.USER))
            assertFalse(result) { "USER 역할은 관리자 접근이 불가해야 한다" }
        }

        @Test
        fun `역할 attribute가 없으면 isAdmin이 false를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.isAdmin(exchangeWithRole(null))
            assertFalse(result) { "인증 정보 없을 때 fail-close 정책으로 false를 반환해야 한다" }
        }

        @Test
        fun `attribute가 UserRole 타입이 아니면 isAdmin이 false를 반환해야 한다`() {
            val exchange = mockk<ServerWebExchange>()
            val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to "ADMIN")
            every { exchange.attributes } returns attrs

            val result = AdminAuthorizationSupport.isAdmin(exchange)

            assertFalse(result) { "잘못된 타입의 attribute는 false로 처리되어야 한다" }
        }
    }

    // ─── isAnyAdmin ───────────────────────────────────────────────────────────

    @Nested
    inner class IsAnyAdmin {

        @Test
        fun `ADMIN 역할은 isAnyAdmin이 true를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.isAnyAdmin(exchangeWithRole(UserRole.ADMIN))
            assertTrue(result) { "ADMIN 역할은 광범위한 관리자 접근을 허용해야 한다" }
        }

        @Test
        fun `ADMIN_MANAGER 역할은 isAnyAdmin이 true를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.isAnyAdmin(exchangeWithRole(UserRole.ADMIN_MANAGER))
            assertTrue(result) { "ADMIN_MANAGER 역할은 매니저 대시보드 접근을 허용해야 한다" }
        }

        @Test
        fun `ADMIN_DEVELOPER 역할은 isAnyAdmin이 true를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.isAnyAdmin(exchangeWithRole(UserRole.ADMIN_DEVELOPER))
            assertTrue(result) { "ADMIN_DEVELOPER 역할은 광범위한 관리자 접근을 허용해야 한다" }
        }

        @Test
        fun `USER 역할은 isAnyAdmin이 false를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.isAnyAdmin(exchangeWithRole(UserRole.USER))
            assertFalse(result) { "USER 역할은 모든 관리자 접근이 불가해야 한다" }
        }

        @Test
        fun `역할 attribute가 없으면 isAnyAdmin이 false를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.isAnyAdmin(exchangeWithRole(null))
            assertFalse(result) { "인증 정보 없을 때 fail-close 정책으로 false를 반환해야 한다" }
        }
    }

    // ─── currentActor ─────────────────────────────────────────────────────────

    @Nested
    inner class CurrentActor {

        @Test
        fun `userId attribute가 있으면 그 값을 반환해야 한다`() {
            val result = AdminAuthorizationSupport.currentActor(exchangeWithUserId("user-abc-123"))
            assertEquals("user-abc-123", result) { "인증된 userId가 그대로 반환되어야 한다" }
        }

        @Test
        fun `userId attribute가 없으면 anonymous를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.currentActor(exchangeWithUserId(null))
            assertEquals("anonymous", result) { "인증되지 않은 요청은 anonymous 폴백이어야 한다" }
        }

        @Test
        fun `userId가 공백 문자열이면 anonymous를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.currentActor(exchangeWithUserId("   "))
            assertEquals("anonymous", result) { "공백만 있는 userId는 anonymous로 처리되어야 한다" }
        }

        @Test
        fun `userId가 빈 문자열이면 anonymous를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.currentActor(exchangeWithUserId(""))
            assertEquals("anonymous", result) { "빈 userId는 anonymous 폴백이어야 한다" }
        }

        @Test
        fun `userId attribute가 String 타입이 아니면 anonymous를 반환해야 한다`() {
            val exchange = mockk<ServerWebExchange>()
            val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ID_ATTRIBUTE to 42)
            every { exchange.attributes } returns attrs

            val result = AdminAuthorizationSupport.currentActor(exchange)

            assertEquals("anonymous", result) { "잘못된 타입의 userId는 anonymous로 처리되어야 한다" }
        }
    }

    // ─── maskedAdminAccountRef ────────────────────────────────────────────────

    @Nested
    inner class MaskedAdminAccountRef {

        @Test
        fun `null actor는 unknown 참조를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.maskedAdminAccountRef(null)
            assertEquals("admin-account:unknown", result) { "null actor는 unknown 마스킹 참조여야 한다" }
        }

        @Test
        fun `빈 문자열 actor는 unknown 참조를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.maskedAdminAccountRef("")
            assertEquals("admin-account:unknown", result) { "빈 actor는 unknown 마스킹 참조여야 한다" }
        }

        @Test
        fun `공백만 있는 actor는 unknown 참조를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.maskedAdminAccountRef("   ")
            assertEquals("admin-account:unknown", result) { "공백만 있는 actor는 unknown 마스킹 참조여야 한다" }
        }

        @Test
        fun `anonymous actor는 anonymous 참조를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.maskedAdminAccountRef("anonymous")
            assertEquals("admin-account:anonymous", result) { "anonymous actor는 전용 참조로 처리되어야 한다" }
        }

        @Test
        fun `유효한 actor는 admin-account 접두사로 시작하는 해시 참조를 반환해야 한다`() {
            val result = AdminAuthorizationSupport.maskedAdminAccountRef("admin@example.com")
            assertTrue(result.startsWith("admin-account:")) { "마스킹된 참조는 admin-account: 접두사여야 한다" }
        }

        @Test
        fun `해시 접두사는 정확히 12자여야 한다`() {
            val result = AdminAuthorizationSupport.maskedAdminAccountRef("admin@example.com")
            val hashPart = result.removePrefix("admin-account:")
            assertEquals(12, hashPart.length) { "SHA-256 해시 앞 12자만 포함해야 한다. 실제: '$hashPart'" }
        }

        @Test
        fun `동일한 actor는 항상 동일한 해시를 반환해야 한다`() {
            val actor = "admin@example.com"
            val first = AdminAuthorizationSupport.maskedAdminAccountRef(actor)
            val second = AdminAuthorizationSupport.maskedAdminAccountRef(actor)
            assertEquals(first, second) { "동일한 actor는 결정적 해시를 반환해야 한다" }
        }

        @Test
        fun `서로 다른 actor는 서로 다른 해시를 반환해야 한다`() {
            val result1 = AdminAuthorizationSupport.maskedAdminAccountRef("alice@example.com")
            val result2 = AdminAuthorizationSupport.maskedAdminAccountRef("bob@example.com")
            assertTrue(result1 != result2) { "서로 다른 actor는 서로 다른 마스킹 참조여야 한다" }
        }

        @Test
        fun `앞뒤 공백이 있는 actor는 정규화 후 동일한 해시를 반환해야 한다`() {
            val clean = AdminAuthorizationSupport.maskedAdminAccountRef("admin@example.com")
            val padded = AdminAuthorizationSupport.maskedAdminAccountRef("  admin@example.com  ")
            assertEquals(clean, padded) { "공백 정규화 후 동일한 해시여야 한다" }
        }

        @Test
        fun `해시 부분은 소문자 16진수 문자만 포함해야 한다`() {
            val result = AdminAuthorizationSupport.maskedAdminAccountRef("admin-user-001")
            val hashPart = result.removePrefix("admin-account:")
            assertTrue(hashPart.matches(Regex("[0-9a-f]+"))) {
                "SHA-256 hex 해시는 소문자 16진수여야 한다. 실제: '$hashPart'"
            }
        }
    }

    // ─── UserRole 보조 메서드 검증 ────────────────────────────────────────────

    @Nested
    inner class UserRoleMethods {

        @Test
        fun `ADMIN의 isDeveloperAdmin은 true여야 한다`() {
            assertTrue(UserRole.ADMIN.isDeveloperAdmin()) { "ADMIN은 개발자 관리자 역할이어야 한다" }
        }

        @Test
        fun `ADMIN_DEVELOPER의 isDeveloperAdmin은 true여야 한다`() {
            assertTrue(UserRole.ADMIN_DEVELOPER.isDeveloperAdmin()) { "ADMIN_DEVELOPER는 개발자 관리자 역할이어야 한다" }
        }

        @Test
        fun `ADMIN_MANAGER의 isDeveloperAdmin은 false여야 한다`() {
            assertFalse(UserRole.ADMIN_MANAGER.isDeveloperAdmin()) { "ADMIN_MANAGER는 개발자 컨트롤 서피스에 접근할 수 없어야 한다" }
        }

        @Test
        fun `USER의 isDeveloperAdmin은 false여야 한다`() {
            assertFalse(UserRole.USER.isDeveloperAdmin()) { "USER는 개발자 관리자 역할이 아니어야 한다" }
        }

        @Test
        fun `ADMIN의 isAnyAdmin은 true여야 한다`() {
            assertTrue(UserRole.ADMIN.isAnyAdmin()) { "ADMIN은 모든 관리자 접근이 허용되어야 한다" }
        }

        @Test
        fun `ADMIN_MANAGER의 isAnyAdmin은 true여야 한다`() {
            assertTrue(UserRole.ADMIN_MANAGER.isAnyAdmin()) { "ADMIN_MANAGER는 매니저 관리자 접근이 허용되어야 한다" }
        }

        @Test
        fun `ADMIN_DEVELOPER의 isAnyAdmin은 true여야 한다`() {
            assertTrue(UserRole.ADMIN_DEVELOPER.isAnyAdmin()) { "ADMIN_DEVELOPER는 관리자 접근이 허용되어야 한다" }
        }

        @Test
        fun `USER의 isAnyAdmin은 false여야 한다`() {
            assertFalse(UserRole.USER.isAnyAdmin()) { "USER는 어떤 관리자 접근도 허용되지 않아야 한다" }
        }

        @Test
        fun `ADMIN의 adminScope는 FULL이어야 한다`() {
            assertEquals(AdminScope.FULL, UserRole.ADMIN.adminScope()) { "ADMIN 역할의 범위는 FULL이어야 한다" }
        }

        @Test
        fun `ADMIN_MANAGER의 adminScope는 MANAGER여야 한다`() {
            assertEquals(AdminScope.MANAGER, UserRole.ADMIN_MANAGER.adminScope()) { "ADMIN_MANAGER 역할의 범위는 MANAGER여야 한다" }
        }

        @Test
        fun `ADMIN_DEVELOPER의 adminScope는 DEVELOPER여야 한다`() {
            assertEquals(AdminScope.DEVELOPER, UserRole.ADMIN_DEVELOPER.adminScope()) { "ADMIN_DEVELOPER 역할의 범위는 DEVELOPER여야 한다" }
        }

        @Test
        fun `USER의 adminScope는 null이어야 한다`() {
            assertEquals(null, UserRole.USER.adminScope()) { "USER 역할은 관리자 범위가 없어야 한다" }
        }
    }
}
