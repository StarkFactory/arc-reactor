package com.arc.reactor.agent.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * AgentErrorCode 열거형, ErrorMessageResolver 인터페이스,
 * DefaultErrorMessageResolver 구현체에 대한 단위 테스트.
 */
class AgentErrorCodeTest {

    @Nested
    inner class AgentErrorCodeValues {

        @Test
        fun `모든 에러 코드는 비어 있지 않은 기본 메시지를 가져야 한다`() {
            for (code in AgentErrorCode.entries) {
                assertTrue(code.defaultMessage.isNotBlank()) {
                    "${code.name}의 defaultMessage가 비어 있으면 안 된다"
                }
            }
        }

        @Test
        fun `RATE_LIMITED는 올바른 기본 메시지를 가져야 한다`() {
            assertEquals(
                "Rate limit exceeded. Please try again later.",
                AgentErrorCode.RATE_LIMITED.defaultMessage
            ) { "RATE_LIMITED의 defaultMessage가 일치하지 않는다" }
        }

        @Test
        fun `TIMEOUT은 올바른 기본 메시지를 가져야 한다`() {
            assertEquals(
                "Request timed out.",
                AgentErrorCode.TIMEOUT.defaultMessage
            ) { "TIMEOUT의 defaultMessage가 일치하지 않는다" }
        }

        @Test
        fun `CONTEXT_TOO_LONG은 올바른 기본 메시지를 가져야 한다`() {
            assertEquals(
                "Input is too long. Please reduce the content.",
                AgentErrorCode.CONTEXT_TOO_LONG.defaultMessage
            ) { "CONTEXT_TOO_LONG의 defaultMessage가 일치하지 않는다" }
        }

        @Test
        fun `BUDGET_EXHAUSTED는 올바른 기본 메시지를 가져야 한다`() {
            assertEquals(
                "Token budget exhausted. Response may be incomplete.",
                AgentErrorCode.BUDGET_EXHAUSTED.defaultMessage
            ) { "BUDGET_EXHAUSTED의 defaultMessage가 일치하지 않는다" }
        }

        @Test
        fun `PLAN_VALIDATION_FAILED는 올바른 기본 메시지를 가져야 한다`() {
            assertEquals(
                "Plan validation failed. The plan contains invalid or unauthorized tools.",
                AgentErrorCode.PLAN_VALIDATION_FAILED.defaultMessage
            ) { "PLAN_VALIDATION_FAILED의 defaultMessage가 일치하지 않는다" }
        }

        @Test
        fun `열거형에 예상되는 총 코드 수가 있어야 한다`() {
            val expectedCodes = setOf(
                "RATE_LIMITED", "TIMEOUT", "CONTEXT_TOO_LONG", "TOOL_ERROR",
                "GUARD_REJECTED", "HOOK_REJECTED", "INVALID_RESPONSE",
                "OUTPUT_GUARD_REJECTED", "OUTPUT_TOO_SHORT", "CIRCUIT_BREAKER_OPEN",
                "BUDGET_EXHAUSTED", "PLAN_VALIDATION_FAILED", "UNKNOWN"
            )
            val actualCodes = AgentErrorCode.entries.map { it.name }.toSet()

            assertEquals(expectedCodes, actualCodes) {
                "AgentErrorCode 열거값 목록이 예상과 다르다 — 추가/제거 시 이 테스트를 업데이트할 것"
            }
        }

        @Test
        fun `valueOf로 이름에 해당하는 코드를 조회할 수 있어야 한다`() {
            val code = AgentErrorCode.valueOf("UNKNOWN")

            assertNotNull(code) { "UNKNOWN 코드는 반드시 존재해야 한다" }
            assertEquals(AgentErrorCode.UNKNOWN, code) { "valueOf 결과가 UNKNOWN이어야 한다" }
        }
    }

    @Nested
    inner class DefaultErrorMessageResolverBehavior {

        private val resolver = DefaultErrorMessageResolver()

        @Test
        fun `TOOL_ERROR는 코드 기본 메시지를 반환해야 한다`() {
            val result = resolver.resolve(AgentErrorCode.TOOL_ERROR, "원본 에러 메시지")

            assertEquals(AgentErrorCode.TOOL_ERROR.defaultMessage, result) {
                "TOOL_ERROR resolve 결과가 defaultMessage와 달라서는 안 된다"
            }
        }

        @Test
        fun `RATE_LIMITED는 코드 기본 메시지를 반환해야 한다`() {
            val result = resolver.resolve(AgentErrorCode.RATE_LIMITED, null)

            assertEquals(AgentErrorCode.RATE_LIMITED.defaultMessage, result) {
                "RATE_LIMITED resolve 결과가 defaultMessage와 달라서는 안 된다"
            }
        }

        @Test
        fun `TIMEOUT은 코드 기본 메시지를 반환해야 한다`() {
            val result = resolver.resolve(AgentErrorCode.TIMEOUT, "connection timeout")

            assertEquals(AgentErrorCode.TIMEOUT.defaultMessage, result) {
                "TIMEOUT resolve 결과가 defaultMessage와 달라서는 안 된다"
            }
        }

        @Test
        fun `UNKNOWN은 코드 기본 메시지를 반환해야 한다`() {
            val result = resolver.resolve(AgentErrorCode.UNKNOWN, null)

            assertEquals(AgentErrorCode.UNKNOWN.defaultMessage, result) {
                "UNKNOWN resolve 결과가 defaultMessage와 달라서는 안 된다"
            }
        }

        @Test
        fun `originalMessage가 null이어도 모든 코드에 대해 예외 없이 동작해야 한다`() {
            for (code in AgentErrorCode.entries) {
                val result = resolver.resolve(code, null)

                assertNotNull(result) { "${code.name}에 대해 null 반환은 허용되지 않는다" }
                assertTrue(result.isNotBlank()) { "${code.name}에 대해 빈 메시지 반환은 허용되지 않는다" }
            }
        }

        @Test
        fun `반환된 메시지에 원본 예외 메시지가 노출되지 않아야 한다`() {
            val sensitiveMessage = "DB password: secret123"

            for (code in AgentErrorCode.entries) {
                val result = resolver.resolve(code, sensitiveMessage)

                assertTrue(!result.contains(sensitiveMessage)) {
                    "${code.name} resolve 결과에 원본 예외 메시지가 포함되어서는 안 된다 (보안 규칙 위반)"
                }
            }
        }
    }

    @Nested
    inner class CustomErrorMessageResolverBehavior {

        @Test
        fun `커스텀 resolver가 코드에 따라 다른 메시지를 반환할 수 있어야 한다`() {
            val customResolver = ErrorMessageResolver { code, _ ->
                when (code) {
                    AgentErrorCode.RATE_LIMITED -> "요청 한도 초과"
                    AgentErrorCode.TIMEOUT -> "요청 시간 초과"
                    else -> code.defaultMessage
                }
            }

            assertEquals("요청 한도 초과", customResolver.resolve(AgentErrorCode.RATE_LIMITED, null)) {
                "커스텀 resolver가 RATE_LIMITED에 대해 한글 메시지를 반환해야 한다"
            }
            assertEquals("요청 시간 초과", customResolver.resolve(AgentErrorCode.TIMEOUT, null)) {
                "커스텀 resolver가 TIMEOUT에 대해 한글 메시지를 반환해야 한다"
            }
            assertEquals(
                AgentErrorCode.UNKNOWN.defaultMessage,
                customResolver.resolve(AgentErrorCode.UNKNOWN, null)
            ) {
                "매핑되지 않은 코드는 기본 메시지를 반환해야 한다"
            }
        }

        @Test
        fun `ErrorMessageResolver가 fun interface로 람다 구현이 가능해야 한다`() {
            val resolver: ErrorMessageResolver = ErrorMessageResolver { _, _ -> "고정 메시지" }

            val result = resolver.resolve(AgentErrorCode.TOOL_ERROR, "anything")

            assertEquals("고정 메시지", result) {
                "fun interface 람다 구현이 올바르게 동작해야 한다"
            }
        }
    }
}
