package com.arc.reactor.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [formatBoundaryViolation] 및 [formatBoundaryRuleViolation]에 대한 테스트.
 *
 * 경계 위반 메시지 포매터가 일관된 형식을 생성하는지 검증한다.
 * 로그 분석 및 모니터링에서 파싱 가능한 형식이어야 한다.
 */
class BoundaryViolationSupportTest {

    @Nested
    inner class FormatBoundaryViolation {

        @Test
        fun `violation 필드가 대괄호 안에 포함된다`() {
            val result = formatBoundaryViolation(
                violation = "INPUT_TOO_LONG",
                policy = "chat-policy",
                limit = 1000,
                actual = 1500
            )

            assertTrue(result.contains("[INPUT_TOO_LONG]")) {
                "violation 종류가 대괄호로 감싸져야 한다, 결과: $result"
            }
        }

        @Test
        fun `policy 값이 메시지에 포함된다`() {
            val result = formatBoundaryViolation(
                violation = "TOKEN_EXCEEDED",
                policy = "strict-guard",
                limit = 500,
                actual = 600
            )

            assertTrue(result.contains("policy=strict-guard")) {
                "policy 이름이 메시지에 포함되어야 한다, 결과: $result"
            }
        }

        @Test
        fun `limit 값이 메시지에 포함된다`() {
            val result = formatBoundaryViolation(
                violation = "TOKEN_EXCEEDED",
                policy = "strict-guard",
                limit = 500,
                actual = 600
            )

            assertTrue(result.contains("limit=500")) {
                "limit 값이 메시지에 포함되어야 한다, 결과: $result"
            }
        }

        @Test
        fun `actual 값이 메시지에 포함된다`() {
            val result = formatBoundaryViolation(
                violation = "TOKEN_EXCEEDED",
                policy = "strict-guard",
                limit = 500,
                actual = 600
            )

            assertTrue(result.contains("actual=600")) {
                "actual 값이 메시지에 포함되어야 한다, 결과: $result"
            }
        }

        @Test
        fun `전체 포맷이 정확하게 일치한다`() {
            val result = formatBoundaryViolation(
                violation = "INPUT_TOO_LONG",
                policy = "chat-policy",
                limit = 1000,
                actual = 1500
            )

            assertEquals(
                "Boundary violation [INPUT_TOO_LONG]: policy=chat-policy, limit=1000, actual=1500",
                result
            ) { "전체 포맷 문자열이 정확히 일치해야 한다" }
        }

        @Test
        fun `limit과 actual이 동일한 경계값에서도 올바르게 포맷된다`() {
            val result = formatBoundaryViolation(
                violation = "BOUNDARY_EXACT",
                policy = "test-policy",
                limit = 100,
                actual = 100
            )

            assertEquals(
                "Boundary violation [BOUNDARY_EXACT]: policy=test-policy, limit=100, actual=100",
                result
            ) { "limit == actual 경계값도 올바르게 포맷되어야 한다" }
        }

        @Test
        fun `limit이 0이고 actual도 0인 경우 올바르게 포맷된다`() {
            val result = formatBoundaryViolation(
                violation = "ZERO_LIMIT",
                policy = "zero-policy",
                limit = 0,
                actual = 0
            )

            assertTrue(result.contains("limit=0")) { "limit=0이 포함되어야 한다, 결과: $result" }
            assertTrue(result.contains("actual=0")) { "actual=0이 포함되어야 한다, 결과: $result" }
        }

        @Test
        fun `음수 값도 올바르게 포맷된다`() {
            val result = formatBoundaryViolation(
                violation = "NEGATIVE_TEST",
                policy = "test",
                limit = -1,
                actual = -5
            )

            assertTrue(result.contains("limit=-1")) { "음수 limit이 포함되어야 한다, 결과: $result" }
            assertTrue(result.contains("actual=-5")) { "음수 actual이 포함되어야 한다, 결과: $result" }
        }

        @Test
        fun `메시지가 Boundary violation으로 시작한다`() {
            val result = formatBoundaryViolation(
                violation = "ANY_VIOLATION",
                policy = "any-policy",
                limit = 10,
                actual = 20
            )

            assertTrue(result.startsWith("Boundary violation")) {
                "메시지가 'Boundary violation'으로 시작해야 한다, 결과: $result"
            }
        }
    }

    @Nested
    inner class FormatBoundaryRuleViolation {

        @Test
        fun `rule 필드가 대괄호 안에 포함된다`() {
            val result = formatBoundaryRuleViolation(
                rule = "MAX_MESSAGE_LENGTH",
                actual = 2000,
                limit = 1000
            )

            assertTrue(result.contains("[MAX_MESSAGE_LENGTH]")) {
                "rule 이름이 대괄호로 감싸져야 한다, 결과: $result"
            }
        }

        @Test
        fun `actual 값이 메시지에 포함된다`() {
            val result = formatBoundaryRuleViolation(
                rule = "MAX_MESSAGE_LENGTH",
                actual = 2000,
                limit = 1000
            )

            assertTrue(result.contains("actual=2000")) {
                "actual 값이 메시지에 포함되어야 한다, 결과: $result"
            }
        }

        @Test
        fun `limit 값이 메시지에 포함된다`() {
            val result = formatBoundaryRuleViolation(
                rule = "MAX_MESSAGE_LENGTH",
                actual = 2000,
                limit = 1000
            )

            assertTrue(result.contains("limit=1000")) {
                "limit 값이 메시지에 포함되어야 한다, 결과: $result"
            }
        }

        @Test
        fun `전체 포맷이 정확하게 일치한다`() {
            val result = formatBoundaryRuleViolation(
                rule = "MAX_MESSAGE_LENGTH",
                actual = 2000,
                limit = 1000
            )

            assertEquals(
                "Boundary violation [MAX_MESSAGE_LENGTH]: actual=2000, limit=1000",
                result
            ) { "전체 포맷 문자열이 정확히 일치해야 한다" }
        }

        @Test
        fun `actual과 limit 순서가 formatBoundaryViolation과 다르다`() {
            val ruleResult = formatBoundaryRuleViolation(rule = "RULE", actual = 5, limit = 3)

            // formatBoundaryRuleViolation은 actual이 limit보다 먼저 온다
            val actualIndex = ruleResult.indexOf("actual=")
            val limitIndex = ruleResult.indexOf("limit=")

            assertTrue(actualIndex < limitIndex) {
                "formatBoundaryRuleViolation에서 actual이 limit보다 먼저 와야 한다, 결과: $ruleResult"
            }
        }

        @Test
        fun `formatBoundaryViolation에서는 limit이 actual보다 먼저 온다`() {
            val violationResult = formatBoundaryViolation(
                violation = "V",
                policy = "p",
                limit = 3,
                actual = 5
            )

            val limitIndex = violationResult.indexOf("limit=")
            val actualIndex = violationResult.indexOf("actual=")

            assertTrue(limitIndex < actualIndex) {
                "formatBoundaryViolation에서 limit이 actual보다 먼저 와야 한다, 결과: $violationResult"
            }
        }

        @Test
        fun `큰 정수 값도 올바르게 포맷된다`() {
            val result = formatBoundaryRuleViolation(
                rule = "LARGE_VALUE_RULE",
                actual = Int.MAX_VALUE,
                limit = 1000000
            )

            assertTrue(result.contains("actual=${Int.MAX_VALUE}")) {
                "Int.MAX_VALUE가 올바르게 포맷되어야 한다, 결과: $result"
            }
            assertTrue(result.contains("limit=1000000")) {
                "큰 limit 값이 올바르게 포맷되어야 한다, 결과: $result"
            }
        }
    }

    @Nested
    inner class ConsistencyAcrossFunctions {

        @Test
        fun `두 함수 모두 Boundary violation 접두사를 사용한다`() {
            val v1 = formatBoundaryViolation("V", "p", 1, 2)
            val v2 = formatBoundaryRuleViolation("R", 2, 1)

            assertTrue(v1.startsWith("Boundary violation")) {
                "formatBoundaryViolation이 'Boundary violation'으로 시작해야 한다, 결과: $v1"
            }
            assertTrue(v2.startsWith("Boundary violation")) {
                "formatBoundaryRuleViolation이 'Boundary violation'으로 시작해야 한다, 결과: $v2"
            }
        }

        @Test
        fun `두 함수 모두 대괄호로 식별자를 감싼다`() {
            val v1 = formatBoundaryViolation("MY_VIOLATION", "p", 1, 2)
            val v2 = formatBoundaryRuleViolation("MY_RULE", 2, 1)

            assertTrue(v1.contains("[MY_VIOLATION]")) {
                "formatBoundaryViolation이 대괄호로 violation을 감싸야 한다, 결과: $v1"
            }
            assertTrue(v2.contains("[MY_RULE]")) {
                "formatBoundaryRuleViolation이 대괄호로 rule을 감싸야 한다, 결과: $v2"
            }
        }
    }
}
