package com.arc.reactor.approval

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [RedactedApprovalContextResolver] 단위 테스트.
 *
 * R228: delegate가 반환한 [ApprovalContext]의 텍스트 필드를 정규식으로 마스킹하는
 * 데코레이터의 동작을 검증한다. 기본 PII 패턴, 커스텀 패턴, fail-safe, chain 통합 등.
 */
class RedactedApprovalContextResolverTest {

    private val sampleArgs = mapOf("issueKey" to "HRFW-42")

    @Nested
    inner class NullPassthrough {

        @Test
        fun `delegate가 null을 반환하면 데코레이터도 null이어야 한다`() {
            val delegate = mockk<ApprovalContextResolver>()
            every { delegate.resolve(any(), any()) } returns null

            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("any_tool", emptyMap())

            assertNull(result) { "null pass-through" }
        }

        @Test
        fun `reason과 action과 impactScope가 모두 null이어도 데코레이터는 context 객체를 그대로 반환해야 한다`() {
            val delegate = mockk<ApprovalContextResolver>()
            every { delegate.resolve(any(), any()) } returns ApprovalContext(
                reason = null,
                action = null,
                impactScope = null,
                reversibility = Reversibility.REVERSIBLE
            )

            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertNotNull(result) { "null 필드는 개별 pass-through" }
            assertNull(result!!.reason)
            assertNull(result.action)
            assertNull(result.impactScope)
            assertEquals(Reversibility.REVERSIBLE, result.reversibility) {
                "reversibility enum은 변경되지 않아야 한다"
            }
        }
    }

    @Nested
    inner class EmailRedaction {

        @Test
        fun `이메일은 기본 패턴으로 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                reason = "Jira 검색 대상: user@company.com 에게 할당된 이슈"
            )
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertNotNull(result)
            assertFalse(result!!.reason!!.contains("user@company.com")) {
                "원본 이메일이 남아있으면 안 된다"
            }
            assertTrue(result.reason.contains("***")) {
                "마스킹 대체 문자열이 포함되어야 한다"
            }
        }

        @Test
        fun `한 문자열에 여러 이메일이 있으면 모두 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                action = "from alice@company.com to bob@external.org cc carol@test.co.kr"
            )
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            val action = result!!.action!!
            assertFalse(action.contains("alice@company.com"))
            assertFalse(action.contains("bob@external.org"))
            assertFalse(action.contains("carol@test.co.kr"))
        }

        @Test
        fun `하이픈과 점 포함 이메일도 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                impactScope = "first.last-name+tag@sub.example-corp.io"
            )
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertEquals("***", result!!.impactScope) {
                "복합 이메일 전체가 한 번에 매칭되어 ***로 대체되어야 한다"
            }
        }
    }

    @Nested
    inner class TokenRedaction {

        @Test
        fun `Bearer 토큰은 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                reason = "호출: Authorization Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
            )
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertFalse(result!!.reason!!.contains("eyJhbGciOiJIUzI1NiI")) {
                "JWT 페이로드가 남아있으면 안 된다"
            }
        }

        @Test
        fun `Bearer 소문자도 IGNORE_CASE로 마스킹되어야 한다`() {
            val delegate = staticDelegate(reason = "bearer abc123def456token")
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertFalse(result!!.reason!!.contains("abc123def456token")) {
                "소문자 bearer도 매칭"
            }
        }

        @Test
        fun `Atlassian granular 토큰은 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                action = "호출 헤더에 ATATT3xFfGF0abcdefg1234567-_=xyz"
            )
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertFalse(result!!.action!!.contains("ATATT3xFfGF0abcdefg1234567")) {
                "Atlassian granular 토큰이 남아있으면 안 된다"
            }
        }

        @Test
        fun `Slack 토큰은 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                reason = "호출: xoxb-1234567890-abcdef xoxp-9876543210"
            )
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertFalse(result!!.reason!!.contains("xoxb-1234567890"))
            assertFalse(result.reason.contains("xoxp-9876543210"))
        }
    }

    @Nested
    inner class PhoneNumberRedaction {

        @Test
        fun `한국 휴대폰 번호는 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                impactScope = "담당자 연락처: 010-1234-5678"
            )
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertFalse(result!!.impactScope!!.contains("010-1234-5678"))
        }

        @Test
        fun `3자리 가운데 번호도 마스킹되어야 한다`() {
            val delegate = staticDelegate(reason = "연락처 011-234-5678")
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertFalse(result!!.reason!!.contains("011-234-5678"))
        }

        @Test
        fun `국제 표기 한국 휴대폰도 마스킹되어야 한다`() {
            val delegate = staticDelegate(reason = "International: +82-10-1234-5678")
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertFalse(result!!.reason!!.contains("+82-10-1234-5678"))
            assertFalse(result.reason.contains("82-10-1234-5678"))
        }
    }

    @Nested
    inner class KoreanResidentNumberRedaction {

        @Test
        fun `주민등록번호 패턴은 마스킹되어야 한다`() {
            val delegate = staticDelegate(impactScope = "주민번호: 900101-1234567")
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertFalse(result!!.impactScope!!.contains("900101-1234567"))
        }

        @Test
        fun `첫 자리가 5 이상인 경우 마스킹되지 않아야 한다`() {
            // 오탐 방지: 첫 자리 [1-4] 만 매칭
            val delegate = staticDelegate(impactScope = "ID: 900101-9999999")
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertTrue(result!!.impactScope!!.contains("900101-9999999")) {
                "첫 자리가 9이면 주민번호가 아니므로 마스킹 대상 아님"
            }
        }
    }

    @Nested
    inner class CustomPatterns {

        @Test
        fun `사용자 정의 패턴이 기본 패턴에 추가되어야 한다`() {
            val delegate = staticDelegate(
                reason = "내부 ID: INTERNAL-12345678 그리고 이메일: user@company.com"
            )
            val redacted = RedactedApprovalContextResolver(
                delegate = delegate,
                additionalPatterns = listOf(Regex("""INTERNAL-\d{8}"""))
            )
            val result = redacted.resolve("tool", emptyMap())

            assertFalse(result!!.reason!!.contains("INTERNAL-12345678")) {
                "커스텀 패턴 매칭"
            }
            assertFalse(result.reason.contains("user@company.com")) {
                "기본 이메일 패턴도 함께 적용"
            }
        }

        @Test
        fun `사용자 패턴만으로도 동작해야 한다`() {
            val delegate = staticDelegate(impactScope = "PROJECT-ABC123")
            val redacted = RedactedApprovalContextResolver(
                delegate = delegate,
                additionalPatterns = listOf(Regex("""PROJECT-[A-Z]+\d+"""))
            )
            val result = redacted.resolve("tool", emptyMap())

            assertFalse(result!!.impactScope!!.contains("PROJECT-ABC123"))
        }

        @Test
        fun `기본 패턴 + 추가 패턴 합계 개수가 정확해야 한다`() {
            val additional = listOf(Regex("a"), Regex("b"), Regex("c"))
            val redacted = RedactedApprovalContextResolver(
                delegate = mockk(),
                additionalPatterns = additional
            )
            val expected = RedactedApprovalContextResolver.DEFAULT_PATTERNS.size + 3
            assertEquals(expected, redacted.patternCount()) {
                "기본 패턴 + 추가 3개"
            }
        }
    }

    @Nested
    inner class CustomReplacement {

        @Test
        fun `사용자 정의 replacement가 적용되어야 한다`() {
            val delegate = staticDelegate(reason = "email: user@company.com")
            val redacted = RedactedApprovalContextResolver(
                delegate = delegate,
                replacement = "<REDACTED>"
            )
            val result = redacted.resolve("tool", emptyMap())

            assertTrue(result!!.reason!!.contains("<REDACTED>"))
            assertFalse(result.reason.contains("***")) {
                "기본 *** 가 사용되면 안 된다"
            }
        }
    }

    @Nested
    inner class ReversibilityPreservation {

        @Test
        fun `reversibility enum은 마스킹 대상이 아니어야 한다`() {
            val delegate = staticDelegate(
                reason = "secret",
                reversibility = Reversibility.IRREVERSIBLE
            )
            val redacted = RedactedApprovalContextResolver(delegate)
            val result = redacted.resolve("tool", emptyMap())

            assertEquals(Reversibility.IRREVERSIBLE, result!!.reversibility) {
                "reversibility는 그대로 유지되어야 한다"
            }
        }
    }

    @Nested
    inner class ChainIntegration {

        @Test
        fun `ChainedApprovalContextResolver를 감싸도 정상 동작해야 한다`() {
            val chain = ChainedApprovalContextResolver(
                AtlassianApprovalContextResolver(),
                HeuristicApprovalContextResolver()
            )
            val redacted = RedactedApprovalContextResolver(chain)

            // atlassian 도구는 AtlassianResolver가 처리하고, 그 결과에 이메일이 포함되면 마스킹됨
            val result = redacted.resolve(
                "jira_search_my_issues_by_text",
                mapOf("requesterEmail" to "user@company.com", "text" to "백로그")
            )
            assertNotNull(result) { "체인이 결과를 반환해야 한다" }

            // AtlassianResolver가 requesterEmail을 impactScope로 추출할 수 있음 → 마스킹 확인
            val allText = "${result!!.reason} ${result.action} ${result.impactScope}"
            assertFalse(allText.contains("user@company.com")) {
                "체인 결과의 모든 텍스트 필드에 이메일이 남아있으면 안 된다: $allText"
            }
        }

        @Test
        fun `체인이 null을 반환하면 데코레이터도 null을 반환해야 한다`() {
            val chain = ChainedApprovalContextResolver(emptyList())
            val redacted = RedactedApprovalContextResolver(chain)

            val result = redacted.resolve("tool", emptyMap())
            assertNull(result) { "빈 체인 → null" }
        }
    }

    @Nested
    inner class AtlassianIntegration {

        @Test
        fun `Atlassian 리졸버가 반환한 이메일이 마스킹되어야 한다`() {
            val atlassian = AtlassianApprovalContextResolver()
            val redacted = RedactedApprovalContextResolver(atlassian)

            val result = redacted.resolve(
                "jira_my_open_issues",
                mapOf("requesterEmail" to "alice@company.com")
            )
            assertNotNull(result)
            val allText = "${result!!.reason} ${result.action} ${result.impactScope}"
            assertFalse(allText.contains("alice@company.com")) {
                "Atlassian resolver의 impactScope에 들어간 이메일이 마스킹되어야 한다: $allText"
            }
        }

        @Test
        fun `이메일이 없는 호출은 원본 그대로 전달되어야 한다`() {
            val atlassian = AtlassianApprovalContextResolver()
            val redacted = RedactedApprovalContextResolver(atlassian)

            val result = redacted.resolve(
                "jira_get_issue",
                mapOf("issueKey" to "HRFW-5695")
            )
            assertNotNull(result)
            assertEquals("HRFW-5695", result!!.impactScope) {
                "이슈 키는 PII가 아니므로 마스킹되지 않아야 한다"
            }
            assertTrue(result.reason!!.contains("Jira")) {
                "reason의 일반 텍스트는 마스킹되지 않아야 한다"
            }
        }
    }

    @Nested
    inner class PublicConstants {

        @Test
        fun `DEFAULT_REPLACEMENT은 별표 세 개여야 한다`() {
            assertEquals("***", RedactedApprovalContextResolver.DEFAULT_REPLACEMENT)
        }

        @Test
        fun `SAFE_FALLBACK은 REDACTED 표시여야 한다`() {
            assertEquals("[REDACTED]", RedactedApprovalContextResolver.SAFE_FALLBACK)
        }

        @Test
        fun `DEFAULT_PATTERNS는 최소 5개 이상이어야 한다`() {
            assertTrue(RedactedApprovalContextResolver.DEFAULT_PATTERNS.size >= 5) {
                "기본 패턴은 최소 이메일/Bearer/Atlassian/Slack/전화 등을 포함해야 한다"
            }
        }
    }
}

/**
 * 정적 컨텍스트를 반환하는 리졸버 — 테스트용 fixture.
 */
private fun staticDelegate(
    reason: String? = null,
    action: String? = null,
    impactScope: String? = null,
    reversibility: Reversibility = Reversibility.REVERSIBLE
): ApprovalContextResolver = ApprovalContextResolver { _, _ ->
    ApprovalContext(
        reason = reason,
        action = action,
        impactScope = impactScope,
        reversibility = reversibility
    )
}
