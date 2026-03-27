package com.arc.reactor.controller

import com.arc.reactor.auth.AdminAuthorizationSupport.maskedAdminAccountRef
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

/**
 * OutputGuardRuleController에 대한 테스트.
 *
 * 출력 가드 규칙 REST API의 동작을 검증합니다.
 */
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
        fun `rule for admin를 생성한다`() {
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

            assertEquals(HttpStatus.CREATED, response.statusCode) { "규칙 생성 응답이 201이어야 한다" }
            assertEquals(OutputGuardRuleAction.REJECT, captured.captured.action) { "저장된 규칙의 액션이 REJECT여야 한다" }
            assertEquals(10, captured.captured.priority) { "저장된 규칙의 우선순위가 10이어야 한다" }
            assertTrue(captured.captured.id.isNotBlank()) { "생성된 규칙은 빈 값이 아닌 ID를 가져야 한다" }
            verifyOrder {
                store.save(any())
                invalidationBus.touch()
                auditStore.save(any())
            }
        }

        @Test
        fun `non-admin에 대해 403를 반환한다`() {
            val response = controller.createRule(
                CreateOutputGuardRuleRequest(
                    name = "Secret",
                    pattern = "(?i)secret",
                    action = "REJECT"
                ),
                userExchange()
            )

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "비관리자 규칙 생성 요청은 403이어야 한다" }
        }
    }

    @Nested
    inner class ListRules {
        @Test
        fun `mapped rule list를 반환한다`() {
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

            val response = controller.listRules(adminExchange())
            assertEquals(HttpStatus.OK, response.statusCode) { "규칙 목록 조회가 200이어야 한다" }
            @Suppress("UNCHECKED_CAST")
            val result = response.body as List<OutputGuardRuleResponse>

            assertEquals(1, result.size) { "규칙이 1개 반환되어야 한다" }
            assertEquals("r1", result[0].id) { "규칙 ID가 r1이어야 한다" }
            assertEquals("MASK", result[0].action) { "규칙 액션이 MASK여야 한다" }
            assertEquals("[REDACTED]", result[0].replacement) { "대체 텍스트가 [REDACTED]여야 한다" }
            assertEquals(5, result[0].priority) { "규칙 우선순위가 5여야 한다" }
        }

        @Test
        fun `non-admin에 대해 403를 반환한다`() {
            val response = controller.listRules(userExchange())
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "비관리자 규칙 목록 요청은 403이어야 한다" }
        }

        @Test
        fun `admin account reference in audit responses를 반환한다`() {
            val rawActor = "80b18ee9-d20d-4359-bc5a-a40c4754f958"
            every { auditStore.list(any()) } returns listOf(
                OutputGuardRuleAuditLog(
                    action = OutputGuardRuleAuditAction.UPDATE,
                    actor = rawActor,
                    ruleId = "r1",
                    detail = "updated"
                )
            )

            val response = controller.listAudits(limit = 10, exchange = adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "관리자는 출력 가드 감사 로그를 조회할 수 있어야 한다" }
            @Suppress("UNCHECKED_CAST")
            val body = response.body as List<OutputGuardRuleAuditResponse>
            assertEquals(1, body.size) { "출력 가드 감사 로그가 1개여야 한다" }
            assertEquals(
                maskedAdminAccountRef(rawActor),
                body.first().actor
            ) { "출력 가드 감사 로그의 actor는 마스킹된 계정 식별자만 노출해야 한다" }
            assertTrue(!body.first().actor.contains(rawActor)) { "출력 가드 감사 로그의 actor에 원시 관리자 계정 식별자가 포함되지 않아야 한다" }
        }
    }

    @Nested
    inner class DeleteRule {
        @Test
        fun `for admin를 삭제한다`() {
            every { store.findById("r1") } returns OutputGuardRule(
                id = "r1",
                name = "rule1",
                pattern = "a",
                action = OutputGuardRuleAction.MASK
            )
            val response = controller.deleteRule("r1", adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "규칙 삭제 응답이 204여야 한다" }
            verify { store.delete("r1") }
            verify { invalidationBus.touch() }
            verify {
                auditStore.save(
                    withArg<OutputGuardRuleAuditLog> {
                        assertEquals(OutputGuardRuleAuditAction.DELETE, it.action) { "감사 로그 액션이 DELETE여야 한다" }
                        assertEquals("r1", it.ruleId) { "감사 로그의 규칙 ID가 r1이어야 한다" }
                    }
                )
            }
        }
    }

    @Nested
    inner class Simulate {
        @Test
        fun `simulates은(는) and returns blocked response`() {
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

            assertEquals(HttpStatus.OK, response.statusCode) { "시뮬레이션 응답이 200이어야 한다" }
            val body = response.body as OutputGuardSimulationResponse
            assertTrue(body.blocked) { "시뮬레이션 결과가 차단됨으로 보고되어야 한다" }
            assertEquals("r1", body.blockedByRuleId) { "차단 규칙 ID가 r1이어야 한다" }
            assertEquals(1, body.matchedRules.size) { "매칭된 규칙이 1개여야 한다" }
            verify {
                auditStore.save(
                    withArg<OutputGuardRuleAuditLog> {
                        assertEquals(OutputGuardRuleAuditAction.SIMULATE, it.action) { "감사 로그 액션이 SIMULATE여야 한다" }
                    }
                )
            }
        }
    }
}
