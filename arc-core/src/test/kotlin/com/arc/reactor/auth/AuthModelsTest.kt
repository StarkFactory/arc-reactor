package com.arc.reactor.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * AuthModels 단위 테스트.
 *
 * AuthProperties 기본값·불변성, User 데이터 클래스 동등성,
 * UserRole 역할 메서드, AdminScope·TokenRevocationStoreType 열거형 완전성을 검증한다.
 */
class AuthModelsTest {

    // ─── AuthProperties 기본값 ──────────────────────────────────────────────────

    @Nested
    inner class AuthPropertiesDefaults {

        private val defaults = AuthProperties()

        @Test
        fun `기본 jwtSecret은 빈 문자열이어야 한다`() {
            assertEquals("", defaults.jwtSecret) { "jwtSecret 기본값은 빈 문자열이어야 한다" }
        }

        @Test
        fun `기본 jwtExpirationMs는 24시간(86400000ms)이어야 한다`() {
            assertEquals(86_400_000L, defaults.jwtExpirationMs) {
                "jwtExpirationMs 기본값이 24시간(86400000ms)이어야 한다. 실제: ${defaults.jwtExpirationMs}"
            }
        }

        @Test
        fun `기본 defaultTenantId는 default여야 한다`() {
            assertEquals("default", defaults.defaultTenantId) {
                "defaultTenantId 기본값이 'default'여야 한다. 실제: ${defaults.defaultTenantId}"
            }
        }

        @Test
        fun `기본 selfRegistrationEnabled는 false여야 한다`() {
            assertFalse(defaults.selfRegistrationEnabled) {
                "selfRegistrationEnabled 기본값은 보안상 false여야 한다"
            }
        }

        @Test
        fun `기본 publicPaths에는 로그인·헬스·API 문서 경로가 포함되어야 한다`() {
            val paths = defaults.publicPaths
            assertTrue(paths.contains("/api/auth/login")) { "publicPaths에 /api/auth/login이 포함되어야 한다" }
            assertTrue(paths.contains("/actuator/health")) { "publicPaths에 /actuator/health가 포함되어야 한다" }
            assertTrue(paths.contains("/v3/api-docs")) { "publicPaths에 /v3/api-docs가 포함되어야 한다" }
            assertTrue(paths.contains("/swagger-ui")) { "publicPaths에 /swagger-ui가 포함되어야 한다" }
            assertTrue(paths.contains("/webjars")) { "publicPaths에 /webjars가 포함되어야 한다" }
        }

        @Test
        fun `기본 loginRateLimitPerMinute는 10이어야 한다`() {
            assertEquals(10, defaults.loginRateLimitPerMinute) {
                "loginRateLimitPerMinute 기본값이 10이어야 한다. 실제: ${defaults.loginRateLimitPerMinute}"
            }
        }

        @Test
        fun `기본 trustForwardedHeaders는 false여야 한다`() {
            assertFalse(defaults.trustForwardedHeaders) {
                "trustForwardedHeaders 기본값은 보안상 false여야 한다"
            }
        }

        @Test
        fun `기본 tokenRevocationStore는 MEMORY여야 한다`() {
            assertEquals(TokenRevocationStoreType.MEMORY, defaults.tokenRevocationStore) {
                "tokenRevocationStore 기본값은 단일 인스턴스용 MEMORY여야 한다. 실제: ${defaults.tokenRevocationStore}"
            }
        }
    }

    // ─── AuthProperties 사용자 정의 값 ─────────────────────────────────────────

    @Nested
    inner class AuthPropertiesCustomValues {

        @Test
        fun `커스텀 publicPaths로 기본값을 완전히 대체할 수 있어야 한다`() {
            val custom = AuthProperties(publicPaths = listOf("/public/api"))
            assertEquals(listOf("/public/api"), custom.publicPaths) {
                "커스텀 publicPaths가 기본값을 대체해야 한다"
            }
        }

        @Test
        fun `selfRegistrationEnabled를 true로 설정할 수 있어야 한다`() {
            val props = AuthProperties(selfRegistrationEnabled = true)
            assertTrue(props.selfRegistrationEnabled) { "selfRegistrationEnabled를 true로 설정할 수 있어야 한다" }
        }

        @Test
        fun `tokenRevocationStore를 JDBC로 설정할 수 있어야 한다`() {
            val props = AuthProperties(tokenRevocationStore = TokenRevocationStoreType.JDBC)
            assertEquals(TokenRevocationStoreType.JDBC, props.tokenRevocationStore) {
                "tokenRevocationStore를 JDBC로 설정할 수 있어야 한다"
            }
        }

        @Test
        fun `tokenRevocationStore를 REDIS로 설정할 수 있어야 한다`() {
            val props = AuthProperties(tokenRevocationStore = TokenRevocationStoreType.REDIS)
            assertEquals(TokenRevocationStoreType.REDIS, props.tokenRevocationStore) {
                "tokenRevocationStore를 REDIS로 설정할 수 있어야 한다"
            }
        }

        @Test
        fun `동일한 값으로 생성한 AuthProperties는 동등해야 한다`() {
            val a = AuthProperties(jwtSecret = "secret", jwtExpirationMs = 3600_000L)
            val b = AuthProperties(jwtSecret = "secret", jwtExpirationMs = 3600_000L)
            assertEquals(a, b) { "동일한 값의 AuthProperties는 동등해야 한다" }
        }

        @Test
        fun `다른 jwtSecret을 가진 AuthProperties는 동등하지 않아야 한다`() {
            val a = AuthProperties(jwtSecret = "secret-a")
            val b = AuthProperties(jwtSecret = "secret-b")
            assertNotEquals(a, b) { "jwtSecret이 다른 AuthProperties는 동등하지 않아야 한다" }
        }

        @Test
        fun `copy()로 일부 필드만 변경할 수 있어야 한다`() {
            val original = AuthProperties(jwtSecret = "original-secret")
            val modified = original.copy(jwtExpirationMs = 3600_000L)
            assertEquals("original-secret", modified.jwtSecret) {
                "copy() 후 변경하지 않은 필드는 유지되어야 한다"
            }
            assertEquals(3600_000L, modified.jwtExpirationMs) {
                "copy()로 변경한 필드는 새 값이어야 한다"
            }
        }
    }

    // ─── User 데이터 클래스 ─────────────────────────────────────────────────────

    @Nested
    inner class UserDataClass {

        private val sampleUser = User(
            id = "user-001",
            email = "test@example.com",
            name = "테스트 사용자",
            passwordHash = "\$2a\$10\$hashedpassword"
        )

        @Test
        fun `User의 기본 역할은 USER여야 한다`() {
            assertEquals(UserRole.USER, sampleUser.role) {
                "User 생성 시 role 기본값은 USER여야 한다. 실제: ${sampleUser.role}"
            }
        }

        @Test
        fun `User의 createdAt은 null이 아니어야 한다`() {
            assertNotNull(sampleUser.createdAt) { "User.createdAt은 null이 아니어야 한다" }
        }

        @Test
        fun `User의 createdAt은 현재 시각 이전이거나 같아야 한다`() {
            val now = Instant.now()
            assertFalse(sampleUser.createdAt.isAfter(now)) {
                "User.createdAt은 미래 시각이 아니어야 한다. createdAt=${sampleUser.createdAt}, now=$now"
            }
        }

        @Test
        fun `동일한 필드값의 User는 동등해야 한다`() {
            val fixedTime = Instant.parse("2024-01-01T00:00:00Z")
            val a = User("id-1", "a@b.com", "이름", "hash", UserRole.USER, fixedTime)
            val b = User("id-1", "a@b.com", "이름", "hash", UserRole.USER, fixedTime)
            assertEquals(a, b) { "동일한 필드값의 User는 동등해야 한다" }
        }

        @Test
        fun `id가 다른 User는 동등하지 않아야 한다`() {
            val fixedTime = Instant.parse("2024-01-01T00:00:00Z")
            val a = User("id-1", "a@b.com", "이름", "hash", UserRole.USER, fixedTime)
            val b = User("id-2", "a@b.com", "이름", "hash", UserRole.USER, fixedTime)
            assertNotEquals(a, b) { "id가 다른 User는 동등하지 않아야 한다" }
        }

        @Test
        fun `email이 다른 User는 동등하지 않아야 한다`() {
            val fixedTime = Instant.parse("2024-01-01T00:00:00Z")
            val a = User("id-1", "a@b.com", "이름", "hash", UserRole.USER, fixedTime)
            val b = User("id-1", "c@d.com", "이름", "hash", UserRole.USER, fixedTime)
            assertNotEquals(a, b) { "email이 다른 User는 동등하지 않아야 한다" }
        }

        @Test
        fun `ADMIN 역할로 User를 생성할 수 있어야 한다`() {
            val admin = User("admin-001", "admin@example.com", "관리자", "hash", UserRole.ADMIN)
            assertEquals(UserRole.ADMIN, admin.role) {
                "ADMIN 역할의 User가 올바르게 생성되어야 한다"
            }
        }

        @Test
        fun `copy()로 역할을 승격할 수 있어야 한다`() {
            val promoted = sampleUser.copy(role = UserRole.ADMIN_MANAGER)
            assertEquals(UserRole.ADMIN_MANAGER, promoted.role) {
                "copy()로 역할 승격이 가능해야 한다"
            }
            assertEquals(sampleUser.id, promoted.id) {
                "역할 변경 시 id는 유지되어야 한다"
            }
        }

        @Test
        fun `User의 toString은 passwordHash를 포함한다`() {
            // 보안 참고: User는 data class이므로 toString에 passwordHash 노출.
            // HTTP 응답에 User 객체를 직렬화하지 않도록 서비스 계층에서 DTO 변환 필요.
            val str = sampleUser.toString()
            assertNotNull(str) { "User.toString()은 null이 아니어야 한다" }
            assertTrue(str.contains("user-001")) { "toString()에 id가 포함되어야 한다" }
        }
    }

    // ─── TokenRevocationStoreType 열거형 완전성 ────────────────────────────────

    @Nested
    inner class TokenRevocationStoreTypeEnum {

        @Test
        fun `TokenRevocationStoreType은 정확히 3개 값을 가져야 한다`() {
            val values = TokenRevocationStoreType.entries
            assertEquals(3, values.size) {
                "TokenRevocationStoreType은 MEMORY·JDBC·REDIS 3가지여야 한다. 실제: ${values.map { it.name }}"
            }
        }

        @Test
        fun `MEMORY 값이 존재해야 한다`() {
            val result = TokenRevocationStoreType.valueOf("MEMORY")
            assertEquals(TokenRevocationStoreType.MEMORY, result) {
                "MEMORY 값이 존재해야 한다"
            }
        }

        @Test
        fun `JDBC 값이 존재해야 한다`() {
            val result = TokenRevocationStoreType.valueOf("JDBC")
            assertEquals(TokenRevocationStoreType.JDBC, result) {
                "JDBC 값이 존재해야 한다"
            }
        }

        @Test
        fun `REDIS 값이 존재해야 한다`() {
            val result = TokenRevocationStoreType.valueOf("REDIS")
            assertEquals(TokenRevocationStoreType.REDIS, result) {
                "REDIS 값이 존재해야 한다"
            }
        }
    }

    // ─── AdminScope 열거형 완전성 ──────────────────────────────────────────────

    @Nested
    inner class AdminScopeEnum {

        @Test
        fun `AdminScope는 정확히 3개 값을 가져야 한다`() {
            val values = AdminScope.entries
            assertEquals(3, values.size) {
                "AdminScope는 FULL·MANAGER·DEVELOPER 3가지여야 한다. 실제: ${values.map { it.name }}"
            }
        }

        @Test
        fun `FULL 값이 존재해야 한다`() {
            assertEquals(AdminScope.FULL, AdminScope.valueOf("FULL")) {
                "AdminScope.FULL 값이 존재해야 한다"
            }
        }

        @Test
        fun `MANAGER 값이 존재해야 한다`() {
            assertEquals(AdminScope.MANAGER, AdminScope.valueOf("MANAGER")) {
                "AdminScope.MANAGER 값이 존재해야 한다"
            }
        }

        @Test
        fun `DEVELOPER 값이 존재해야 한다`() {
            assertEquals(AdminScope.DEVELOPER, AdminScope.valueOf("DEVELOPER")) {
                "AdminScope.DEVELOPER 값이 존재해야 한다"
            }
        }
    }

    // ─── UserRole.adminScope() ↔ AdminScope 일관성 ────────────────────────────

    @Nested
    inner class UserRoleAdminScopeConsistency {

        @Test
        fun `adminScope()가 null을 반환하는 역할은 USER뿐이어야 한다`() {
            val nullScopeRoles = UserRole.entries.filter { it.adminScope() == null }
            assertEquals(listOf(UserRole.USER), nullScopeRoles) {
                "adminScope()가 null인 역할은 USER뿐이어야 한다. 실제: ${nullScopeRoles.map { it.name }}"
            }
        }

        @Test
        fun `isAnyAdmin이 true인 역할은 모두 non-null adminScope를 가져야 한다`() {
            val adminRoles = UserRole.entries.filter { it.isAnyAdmin() }
            adminRoles.forEach { role ->
                assertNotNull(role.adminScope()) {
                    "${role.name}.isAnyAdmin()=true이면 adminScope()는 null이 아니어야 한다"
                }
            }
        }

        @Test
        fun `isAnyAdmin이 false인 역할은 모두 null adminScope를 가져야 한다`() {
            val nonAdminRoles = UserRole.entries.filter { !it.isAnyAdmin() }
            nonAdminRoles.forEach { role ->
                assertNull(role.adminScope()) {
                    "${role.name}.isAnyAdmin()=false이면 adminScope()는 null이어야 한다"
                }
            }
        }

        @Test
        fun `isDeveloperAdmin이 true인 역할은 isAnyAdmin도 true여야 한다`() {
            val devAdminRoles = UserRole.entries.filter { it.isDeveloperAdmin() }
            devAdminRoles.forEach { role ->
                assertTrue(role.isAnyAdmin()) {
                    "${role.name}.isDeveloperAdmin()=true이면 isAnyAdmin()도 true여야 한다"
                }
            }
        }

        @Test
        fun `adminScope가 null이 아닌 역할 수와 AdminScope 열거형 값 수는 같아야 한다`() {
            val rolesWithScope = UserRole.entries.count { it.adminScope() != null }
            val scopeCount = AdminScope.entries.size
            assertEquals(scopeCount, rolesWithScope) {
                "adminScope가 있는 역할 수($rolesWithScope)와 AdminScope 열거형 수($scopeCount)가 일치해야 한다"
            }
        }
    }
}
