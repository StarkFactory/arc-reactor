package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserRole
import com.arc.reactor.auth.UserStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

/**
 * RbacController 테스트.
 *
 * 역할 조회, 권한 매트릭스, 사용자 역할 변경을 검증한다.
 */
class RbacControllerTest {

    private lateinit var userStore: UserStore
    private lateinit var auditStore: AdminAuditStore
    private lateinit var controller: RbacController

    @BeforeEach
    fun setup() {
        userStore = mockk(relaxed = true)
        auditStore = mockk(relaxed = true)
        controller = RbacController(userStore, auditStore)
    }

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN,
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to "admin-1"
        )
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER
        )
        return exchange
    }

    private fun sampleUser(
        id: String = "user-1",
        role: UserRole = UserRole.USER
    ) = User(
        id = id,
        email = "user@test.com",
        name = "테스트 사용자",
        passwordHash = "hashed",
        role = role
    )

    // ── 역할 목록 ──

    @Nested
    inner class ListRoles {

        @Test
        fun `관리자는 모든 역할과 권한 매트릭스를 조회할 수 있다`() {
            val result = controller.listRoles(adminExchange())

            assertEquals(HttpStatus.OK, result.statusCode) { "역할 목록 조회는 200이어야 한다" }
            val body = result.body as List<*>
            assertEquals(UserRole.entries.size, body.size) { "모든 UserRole이 포함되어야 한다" }
        }

        @Test
        fun `ADMIN 역할에는 settings write 권한이 포함된다`() {
            val result = controller.listRoles(adminExchange())
            val body = result.body as List<*>
            val admin = body.filterIsInstance<RoleDefinition>().find { it.role == "ADMIN" }

            assertNotNull(admin) { "ADMIN 역할이 존재해야 한다" }
            assertTrue(admin!!.permissions.contains("settings:write")) {
                "ADMIN은 settings:write 권한을 가져야 한다"
            }
        }

        @Test
        fun `USER 역할에는 chat use 권한만 포함된다`() {
            val result = controller.listRoles(adminExchange())
            val body = result.body as List<*>
            val user = body.filterIsInstance<RoleDefinition>().find { it.role == "USER" }

            assertNotNull(user) { "USER 역할이 존재해야 한다" }
            assertTrue(user!!.permissions.contains("chat:use")) {
                "USER는 chat:use 권한을 가져야 한다"
            }
            assertNull(user.scope) { "USER는 adminScope가 null이어야 한다" }
        }

        @Test
        fun `비관리자는 403을 받는다`() {
            val result = controller.listRoles(userExchange())

            assertEquals(HttpStatus.FORBIDDEN, result.statusCode) { "비관리자는 403이어야 한다" }
        }
    }

    // ── 역할 변경 ──

    @Nested
    inner class UpdateRole {

        @Test
        fun `유효한 역할로 변경하면 200을 반환한다`() {
            every { userStore.findById("user-1") } returns sampleUser()
            every { userStore.update(any()) } answers { firstArg() }

            val request = UpdateRoleRequest(role = "ADMIN_MANAGER")
            val result = controller.updateUserRole("user-1", request, adminExchange())

            assertEquals(HttpStatus.OK, result.statusCode) { "역할 변경은 200이어야 한다" }
            verify(exactly = 1) { userStore.update(match { it.role == UserRole.ADMIN_MANAGER }) }
        }

        @Test
        fun `존재하지 않는 사용자 역할 변경은 404를 반환한다`() {
            every { userStore.findById("nonexistent") } returns null

            val request = UpdateRoleRequest(role = "ADMIN")
            val result = controller.updateUserRole("nonexistent", request, adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, result.statusCode) { "미존재 사용자는 404이어야 한다" }
        }

        @Test
        fun `유효하지 않은 역할명은 400을 반환한다`() {
            val request = UpdateRoleRequest(role = "SUPER_ADMIN")
            val result = controller.updateUserRole("user-1", request, adminExchange())

            assertEquals(HttpStatus.BAD_REQUEST, result.statusCode) { "잘못된 역할명은 400이어야 한다" }
        }

        @Test
        fun `비관리자는 403을 받는다`() {
            val request = UpdateRoleRequest(role = "ADMIN")
            val result = controller.updateUserRole("user-1", request, userExchange())

            assertEquals(HttpStatus.FORBIDDEN, result.statusCode) { "비관리자는 403이어야 한다" }
        }
    }
}
