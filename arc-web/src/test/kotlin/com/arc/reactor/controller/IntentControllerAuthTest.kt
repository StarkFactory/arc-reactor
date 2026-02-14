package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.intent.IntentRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class IntentControllerAuthTest {

    private lateinit var registry: IntentRegistry
    private lateinit var controller: IntentController

    @BeforeEach
    fun setup() {
        registry = mockk(relaxed = true)
        controller = IntentController(intentRegistry = registry)
    }

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN
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

    @Test
    fun `list returns 403 for non-admin`() {
        val response = controller.listIntents(userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `get returns 403 for non-admin`() {
        val response = controller.getIntent("anything", userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `list allows admin`() {
        val response = controller.listIntents(adminExchange())
        assertEquals(HttpStatus.OK, response.statusCode)
    }
}

