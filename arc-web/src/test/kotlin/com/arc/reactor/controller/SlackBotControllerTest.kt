package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.multibot.SlackBotInstance
import com.arc.reactor.multibot.SlackBotInstanceStore
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class SlackBotControllerTest {

    private val store = mockk<SlackBotInstanceStore>(relaxed = true)
    private val controller = SlackBotController(store)

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>(relaxed = true)
        val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN)
        every { exchange.attributes } returns attrs
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>(relaxed = true)
        val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER)
        every { exchange.attributes } returns attrs
        return exchange
    }

    private val sampleInstance = SlackBotInstance(
        id = "test-id",
        name = "HR Bot",
        botToken = "test-bot-token-1234567890",
        appToken = "test-app-token-9876543210",
        personaId = "hr-persona"
    )

    @Nested
    inner class ListBots {
        @Test
        fun `관리자는 목록을 조회할 수 있다`() {
            every { store.list() } returns listOf(sampleInstance)
            val response = controller.list(adminExchange())
            response.statusCode shouldBe HttpStatus.OK
        }

        @Test
        fun `토큰이 6자로 마스킹된다`() {
            every { store.list() } returns listOf(sampleInstance)
            val response = controller.list(adminExchange())
            @Suppress("UNCHECKED_CAST")
            val body = response.body as List<SlackBotResponse>
            body[0].botTokenMasked shouldBe "test-b***"
            body[0].appTokenMasked shouldBe "test-a***"
        }

        @Test
        fun `비관리자는 403을 받는다`() {
            val response = controller.list(userExchange())
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }
    }

    @Nested
    inner class CreateBot {
        @Test
        fun `봇 인스턴스를 생성한다`() {
            every { store.list() } returns emptyList()
            every { store.save(any()) } answers { firstArg() }
            val request = CreateSlackBotRequest(
                name = "IT Bot", botToken = "test-bot-it", appToken = "test-app-it", personaId = "it-persona"
            )
            val response = controller.create(request, adminExchange())
            response.statusCode shouldBe HttpStatus.CREATED
            verify { store.save(any()) }
        }

        @Test
        fun `중복 이름은 409를 반환한다`() {
            every { store.list() } returns listOf(sampleInstance)
            val request = CreateSlackBotRequest(
                name = "HR Bot", botToken = "test-bot-new", appToken = "test-app-new", personaId = "p"
            )
            val response = controller.create(request, adminExchange())
            response.statusCode shouldBe HttpStatus.CONFLICT
        }
    }

    @Nested
    inner class DeleteBot {
        @Test
        fun `존재하는 봇을 삭제한다`() {
            every { store.get("test-id") } returns sampleInstance
            val response = controller.delete("test-id", adminExchange())
            response.statusCode shouldBe HttpStatus.NO_CONTENT
            verify { store.delete("test-id") }
        }

        @Test
        fun `존재하지 않는 봇은 404를 반환한다`() {
            every { store.get("unknown") } returns null
            val response = controller.delete("unknown", adminExchange())
            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }
    }
}
