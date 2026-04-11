package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.memory.model.UserMemory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

/**
 * UserMemoryController에 대한 테스트.
 *
 * 사용자 메모리 REST API의 동작을 검증합니다.
 */
class UserMemoryControllerTest {

    private lateinit var userMemoryManager: UserMemoryManager
    private lateinit var controller: UserMemoryController

    @BeforeEach
    fun setUp() {
        userMemoryManager = mockk(relaxed = true)
        controller = UserMemoryController(userMemoryManager)
    }

    @Test
    fun `get user memory은(는) return 403 for non-owner user해야 한다`() = runTest {
        val response = controller.getUserMemory("target-user", userExchange("caller-user"))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "Non-owner USER role should be forbidden"
        }
    }

    @Test
    fun `get user memory은(는) return 404 when no memory exists해야 한다`() = runTest {
        coEvery { userMemoryManager.get("user-1") } returns null

        val response = controller.getUserMemory("user-1", userExchange("user-1"))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
            "Missing memory should return 404"
        }
    }

    @Test
    fun `get user memory은(는) return mapped response for owner해야 한다`() = runTest {
        val updatedAt = Instant.parse("2026-03-01T00:00:00Z")
        coEvery { userMemoryManager.get("user-1") } returns UserMemory(
            userId = "user-1",
            facts = mapOf("team" to "platform"),
            preferences = mapOf("lang" to "ko"),
            recentTopics = listOf("mcp", "rag"),
            updatedAt = updatedAt
        )

        val response = controller.getUserMemory("user-1", userExchange("user-1"))

        assertEquals(HttpStatus.OK, response.statusCode) {
            "Existing memory should return 200"
        }
        val body = assertInstanceOf(UserMemoryResponse::class.java, response.body) {
            "Response body should be UserMemoryResponse"
        }
        assertEquals("platform", body.facts["team"]) { "facts should be mapped" }
        assertEquals("ko", body.preferences["lang"]) { "preferences should be mapped" }
        assertEquals(listOf("mcp", "rag"), body.recentTopics) { "recent topics should be mapped" }
        assertEquals(updatedAt.toString(), body.updatedAt) { "updatedAt should be ISO-8601 string" }
    }

    @Test
    fun `update fact은(는) call manager and return updated true해야 한다`() = runTest {
        coEvery { userMemoryManager.updateFact("user-1", "timezone", "Asia-Seoul") } returns Unit

        val response = controller.updateFact(
            userId = "user-1",
            request = KeyValueRequest("timezone", "Asia-Seoul"),
            exchange = userExchange("user-1")
        )

        assertEquals(HttpStatus.OK, response.statusCode) {
            "Successful fact update should return 200"
        }
        @Suppress("UNCHECKED_CAST")
        val body = response.body as Map<String, Any>
        assertTrue(body["updated"] == true) { "Body should contain updated=true" }
        coVerify(exactly = 1) { userMemoryManager.updateFact("user-1", "timezone", "Asia-Seoul") }
    }

    @Test
    fun `update preference은(는) return 403 for unauthorized user해야 한다`() = runTest {
        val response = controller.updatePreference(
            userId = "target-user",
            request = KeyValueRequest("tone", "formal"),
            exchange = userExchange("caller-user")
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "Non-owner USER role should be forbidden"
        }
        coVerify(exactly = 0) { userMemoryManager.updatePreference(any(), any(), any()) }
    }

    @Test
    fun `delete user memory은(는) reject admin for another user해야 한다`() = runTest {
        val response = controller.deleteUserMemory("target-user", adminExchange("admin-1"))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "Admin should not access another user's memory"
        }
        coVerify(exactly = 0) { userMemoryManager.delete(any()) }
    }

    @Test
    fun `R296 reject anonymous userId in path even when caller has anonymous fallback`() = runTest {
        // R296 fix 검증: JWT 미활성 시 currentActor가 "anonymous" 폴백을 반환하므로
        // userId="anonymous"인 요청은 callerId="anonymous"와 일치하여 self-impersonation
        // 발생. 모든 미인증 호출자가 anonymous 메모리에 read/write/delete 가능했다.
        // R296: ANONYMOUS sentinel을 명시적으로 거부.
        val response = controller.deleteUserMemory("anonymous", noJwtExchange())

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "R296 fix: anonymous userId 경로는 어떤 호출자에게도 거부되어야 한다 " +
                "(JWT 미활성 시 self-impersonation 차단)"
        }
        coVerify(exactly = 0) { userMemoryManager.delete(any()) }
    }

    @Test
    fun `R296 reject anonymous userId case insensitive`() = runTest {
        // R296: 대소문자 변형도 차단 (Anonymous, ANONYMOUS, AnOnYmOuS 등)
        val variants = listOf("Anonymous", "ANONYMOUS", "AnOnYmOuS")
        for (variant in variants) {
            val response = controller.deleteUserMemory(variant, noJwtExchange())
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "R296 fix: anonymous 대소문자 변형 '$variant'도 차단되어야 한다"
            }
        }
        coVerify(exactly = 0) { userMemoryManager.delete(any()) }
    }

    @Test
    fun `R296 reject any request when caller has anonymous fallback`() = runTest {
        // R296: callerId가 anonymous 폴백인 경우(JWT 미활성), userId가 다른 값이라도 거부.
        // 이전 구현은 userId="bob"와 callerId="anonymous"가 다르면 false로 차단했지만,
        // 그 차단은 우연한 결과였고 본질적으로 anonymous bypass의 절반만 막혀있던 셈.
        // R296: callerId가 anonymous면 무조건 거부 — 인증되지 않은 호출 전체를 차단.
        val response = controller.getUserMemory("bob", noJwtExchange())

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "R296 fix: 미인증 호출(callerId=anonymous)은 어떤 userId 요청도 거부되어야 한다"
        }
    }

    private fun userExchange(userId: String): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER,
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to userId
        )
        return exchange
    }

    private fun adminExchange(userId: String): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN,
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to userId
        )
        return exchange
    }

    /**
     * R296: JWT 미활성 시나리오 — USER_ID_ATTRIBUTE가 없어 currentActor가
     * "anonymous" 폴백을 반환하는 상태.
     */
    private fun noJwtExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>()
        return exchange
    }
}
