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

class UserMemoryControllerTest {

    private lateinit var userMemoryManager: UserMemoryManager
    private lateinit var controller: UserMemoryController

    @BeforeEach
    fun setUp() {
        userMemoryManager = mockk(relaxed = true)
        controller = UserMemoryController(userMemoryManager)
    }

    @Test
    fun `get user memory should return 403 for non-owner user`() = runTest {
        val response = controller.getUserMemory("target-user", userExchange("caller-user"))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "Non-owner USER role should be forbidden"
        }
    }

    @Test
    fun `get user memory should return 404 when no memory exists`() = runTest {
        coEvery { userMemoryManager.get("user-1") } returns null

        val response = controller.getUserMemory("user-1", userExchange("user-1"))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
            "Missing memory should return 404"
        }
    }

    @Test
    fun `get user memory should return mapped response for owner`() = runTest {
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
        assertEquals("user-1", body.userId) { "userId should match" }
        assertEquals("platform", body.facts["team"]) { "facts should be mapped" }
        assertEquals("ko", body.preferences["lang"]) { "preferences should be mapped" }
        assertEquals(listOf("mcp", "rag"), body.recentTopics) { "recent topics should be mapped" }
        assertEquals(updatedAt.toString(), body.updatedAt) { "updatedAt should be ISO-8601 string" }
    }

    @Test
    fun `update fact should call manager and return updated true`() = runTest {
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
    fun `update preference should return 403 for unauthorized user`() = runTest {
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
    fun `delete user memory should allow admin for any target`() = runTest {
        coEvery { userMemoryManager.delete("target-user") } returns Unit

        val response = controller.deleteUserMemory("target-user", adminExchange("admin-1"))

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode) {
            "Admin delete should return 204"
        }
        coVerify(exactly = 1) { userMemoryManager.delete("target-user") }
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
}
