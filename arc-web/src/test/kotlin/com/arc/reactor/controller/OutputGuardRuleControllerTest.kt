package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.guard.output.policy.OutputGuardRuleAuditAction
import com.arc.reactor.guard.output.policy.OutputGuardRuleAuditLog
import com.arc.reactor.guard.output.policy.OutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.OutputGuardRule
import com.arc.reactor.guard.output.policy.OutputGuardRuleAction
import com.arc.reactor.guard.output.policy.OutputGuardRuleEvaluator
import com.arc.reactor.guard.output.policy.OutputGuardRuleInvalidationBus
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verifyOrder
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
    private lateinit var auditStore: OutputGuardRuleAuditStore
    private lateinit var invalidationBus: OutputGuardRuleInvalidationBus
    private lateinit var controller: OutputGuardRuleController

    @BeforeEach
    fun setup() {
        store = mockk(relaxed = true)
        auditStore = mockk(relaxed = true)
        invalidationBus = mockk(relaxed = true)
        controller = OutputGuardRuleController(
            store = store,
            auditStore = auditStore,
            invalidationBus = invalidationBus,
            evaluator = OutputGuardRuleEvaluator()
        )
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
                    priority = 10,
                    enabled = true
                ),
                adminExchange()
            )

            assertEquals(HttpStatus.CREATED, response.statusCode)
            assertEquals(OutputGuardRuleAction.REJECT, captured.captured.action)
            assertEquals(10, captured.captured.priority)
            assertTrue(captured.captured.id.isNotBlank())
            verifyOrder {
                store.save(any())
                invalidationBus.touch()
                auditStore.save(any())
            }
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
                    priority = 5,
                    enabled = true,
                    createdAt = now,
                    updatedAt = now
                )
            )

            val result = controller.listRules()

            assertEquals(1, result.size)
            assertEquals("r1", result[0].id)
            assertEquals("MASK", result[0].action)
            assertEquals(5, result[0].priority)
        }
    }

    @Nested
    inner class DeleteRule {
        @Test
        fun `deletes for admin`() {
            every { store.findById("r1") } returns OutputGuardRule(
                id = "r1",
                name = "rule1",
                pattern = "a",
                action = OutputGuardRuleAction.MASK
            )
            val response = controller.deleteRule("r1", adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
            verify { store.delete("r1") }
            verify { invalidationBus.touch() }
            verify {
                auditStore.save(
                    withArg<OutputGuardRuleAuditLog> {
                        assertEquals(OutputGuardRuleAuditAction.DELETE, it.action)
                        assertEquals("r1", it.ruleId)
                    }
                )
            }
        }
    }

    @Nested
    inner class Simulate {
        @Test
        fun `simulates and returns blocked response`() {
            every { store.list() } returns listOf(
                OutputGuardRule(
                    id = "r1",
                    name = "Secret rule",
                    pattern = "(?i)secret",
                    action = OutputGuardRuleAction.REJECT,
                    priority = 1,
                    enabled = true
                )
            )

            val response = controller.simulate(
                OutputGuardSimulationRequest(content = "contains SECRET", includeDisabled = false),
                adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode)
            val body = response.body as OutputGuardSimulationResponse
            assertTrue(body.blocked)
            assertEquals("r1", body.blockedByRuleId)
            assertEquals(1, body.matchedRules.size)
            verify {
                auditStore.save(
                    withArg<OutputGuardRuleAuditLog> {
                        assertEquals(OutputGuardRuleAuditAction.SIMULATE, it.action)
                    }
                )
            }
        }
    }
}
