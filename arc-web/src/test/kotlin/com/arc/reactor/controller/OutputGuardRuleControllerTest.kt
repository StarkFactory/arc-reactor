package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.guard.output.policy.OutputGuardRule
import com.arc.reactor.guard.output.policy.OutputGuardRuleAction
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

class OutputGuardRuleControllerTest {

    private lateinit var store: OutputGuardRuleStore
    private lateinit var controller: OutputGuardRuleController

    @BeforeEach
    fun setup() {
        store = mockk(relaxed = true)
        controller = OutputGuardRuleController(store)
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

    @Nested
    inner class CreateRule {

        @Test
        fun `creates rule for admin`() {
            val captured = slot<OutputGuardRule>()
            every { store.save(capture(captured)) } answers { captured.captured }

            val response = controller.createRule(
                CreateOutputGuardRuleRequest(
                    name = "Secret",
                    pattern = "(?i)secret",
                    action = "REJECT",
                    enabled = true
                ),
                adminExchange()
            )

            assertEquals(HttpStatus.CREATED, response.statusCode)
            assertEquals(OutputGuardRuleAction.REJECT, captured.captured.action)
            assertTrue(captured.captured.id.isNotBlank())
        }

        @Test
        fun `returns 403 for non-admin`() {
            val response = controller.createRule(
                CreateOutputGuardRuleRequest(
                    name = "Secret",
                    pattern = "(?i)secret",
                    action = "REJECT"
                ),
                userExchange()
            )

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        }
    }

    @Nested
    inner class ListRules {
        @Test
        fun `returns mapped rule list`() {
            val now = Instant.parse("2026-02-13T10:00:00Z")
            every { store.list() } returns listOf(
                OutputGuardRule(
                    id = "r1",
                    name = "Mask password",
                    pattern = "(?i)password\\s*[:=]\\s*\\S+",
                    action = OutputGuardRuleAction.MASK,
                    enabled = true,
                    createdAt = now,
                    updatedAt = now
                )
            )

            val result = controller.listRules()

            assertEquals(1, result.size)
            assertEquals("r1", result[0].id)
            assertEquals("MASK", result[0].action)
        }
    }

    @Nested
    inner class DeleteRule {
        @Test
        fun `deletes for admin`() {
            val response = controller.deleteRule("r1", adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
            verify { store.delete("r1") }
        }
    }
}
