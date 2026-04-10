package com.arc.reactor.tool.summarize

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
 * [RedactedToolResponseSummarizer] 단위 테스트.
 *
 * R231: R228 `RedactedApprovalContextResolverTest`와 동일한 패턴.
 * ACI 요약의 text/primaryKey 필드에서 PII가 마스킹되는지 검증한다.
 */
class RedactedToolResponseSummarizerTest {

    @Nested
    inner class NullPassthrough {

        @Test
        fun `delegate가 null을 반환하면 데코레이터도 null이어야 한다`() {
            val delegate = mockk<ToolResponseSummarizer>()
            every { delegate.summarize(any(), any(), any()) } returns null

            val redacted = RedactedToolResponseSummarizer(delegate)
            assertNull(redacted.summarize("tool", "payload", true)) { "null pass-through" }
        }

        @Test
        fun `primaryKey가 null이어도 데코레이터는 그대로 전달해야 한다`() {
            val delegate = staticDelegate(
                text = "no key",
                primaryKey = null,
                kind = SummaryKind.TEXT_FULL
            )
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", true)

            assertNotNull(result)
            assertNull(result!!.primaryKey) { "primaryKey는 null 유지" }
            assertEquals("no key", result.text) { "text는 그대로" }
        }
    }

    @Nested
    inner class EmailRedaction {

        @Test
        fun `text의 이메일은 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                text = "담당자 alice@company.com 의 이슈 3건",
                kind = SummaryKind.LIST_TOP_N
            )
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("jira_search", "", true)

            assertNotNull(result)
            assertFalse(result!!.text.contains("alice@company.com")) {
                "이메일이 text에서 마스킹되어야 한다"
            }
            assertTrue(result.text.contains("***")) { "마스킹 대체 문자열 포함" }
        }

        @Test
        fun `primaryKey의 이메일도 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                text = "summary",
                primaryKey = "user@company.com",
                kind = SummaryKind.STRUCTURED
            )
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("jira_search_users", "", true)

            assertNotNull(result)
            assertEquals("***", result!!.primaryKey) {
                "primaryKey의 이메일 전체가 대체되어야 한다"
            }
        }

        @Test
        fun `여러 이메일이 한 텍스트에 있으면 모두 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                text = "from alice@a.com to bob@b.org cc carol@c.co.kr"
            )
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", true)

            val text = result!!.text
            assertFalse(text.contains("alice@a.com"))
            assertFalse(text.contains("bob@b.org"))
            assertFalse(text.contains("carol@c.co.kr"))
        }
    }

    @Nested
    inner class TokenRedaction {

        @Test
        fun `Bearer JWT 토큰은 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                text = "Authorization Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig 로 호출"
            )
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", true)

            assertFalse(result!!.text.contains("eyJhbGciOiJIUzI1NiJ9"))
        }

        @Test
        fun `Atlassian granular 토큰은 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                text = "토큰 ATATT3xFfGF0abcdefg1234567 사용"
            )
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", true)

            assertFalse(result!!.text.contains("ATATT3xFfGF0abcdefg1234567"))
        }

        @Test
        fun `Slack 토큰은 마스킹되어야 한다`() {
            val delegate = staticDelegate(text = "xoxb-1234567890-abcdef / xoxp-9876543210")
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", true)

            assertFalse(result!!.text.contains("xoxb-1234567890"))
            assertFalse(result.text.contains("xoxp-9876543210"))
        }
    }

    @Nested
    inner class PhoneAndRrnRedaction {

        @Test
        fun `한국 휴대폰 번호는 마스킹되어야 한다`() {
            val delegate = staticDelegate(
                text = "연락처: 010-1234-5678",
                primaryKey = "010-1234-5678"
            )
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", true)

            assertFalse(result!!.text.contains("010-1234-5678"))
            assertEquals("***", result.primaryKey)
        }

        @Test
        fun `국제 표기 휴대폰도 마스킹되어야 한다`() {
            val delegate = staticDelegate(text = "International +82-10-1234-5678")
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", true)

            assertFalse(result!!.text.contains("+82-10-1234-5678"))
        }

        @Test
        fun `주민등록번호는 마스킹되어야 한다`() {
            val delegate = staticDelegate(text = "ID: 900101-1234567")
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", true)

            assertFalse(result!!.text.contains("900101-1234567"))
        }

        @Test
        fun `첫 자리가 5 이상이면 주민번호로 인식되지 않아야 한다`() {
            val delegate = staticDelegate(text = "ID: 900101-9999999")
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", true)

            assertTrue(result!!.text.contains("900101-9999999")) {
                "첫 자리 9는 주민번호 형식이 아니므로 그대로"
            }
        }
    }

    @Nested
    inner class NonTextFieldsPreservation {

        @Test
        fun `kind는 마스킹 대상이 아니어야 한다`() {
            val delegate = staticDelegate(
                text = "email: user@a.com",
                kind = SummaryKind.ERROR_CAUSE_FIRST
            )
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", false)

            assertEquals(SummaryKind.ERROR_CAUSE_FIRST, result!!.kind) {
                "kind enum은 그대로 유지"
            }
        }

        @Test
        fun `originalLength는 그대로 유지되어야 한다`() {
            val delegate = staticDelegate(
                text = "email: user@a.com",
                originalLength = 12345
            )
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", true)

            assertEquals(12345, result!!.originalLength) { "originalLength 그대로" }
        }

        @Test
        fun `itemCount는 그대로 유지되어야 한다`() {
            val delegate = staticDelegate(
                text = "email: user@a.com",
                itemCount = 42
            )
            val redacted = RedactedToolResponseSummarizer(delegate)
            val result = redacted.summarize("tool", "", true)

            assertEquals(42, result!!.itemCount) { "itemCount 그대로" }
        }
    }

    @Nested
    inner class CustomPatterns {

        @Test
        fun `사용자 패턴이 기본 패턴에 추가되어야 한다`() {
            val delegate = staticDelegate(
                text = "내부 ID: INTERNAL-12345678 + 이메일 user@a.com"
            )
            val redacted = RedactedToolResponseSummarizer(
                delegate = delegate,
                additionalPatterns = listOf(Regex("""INTERNAL-\d{8}"""))
            )
            val result = redacted.summarize("tool", "", true)

            assertFalse(result!!.text.contains("INTERNAL-12345678")) { "커스텀 매칭" }
            assertFalse(result.text.contains("user@a.com")) { "기본 이메일도 적용" }
        }

        @Test
        fun `패턴 개수가 정확해야 한다`() {
            val additional = listOf(Regex("a"), Regex("b"))
            val redacted = RedactedToolResponseSummarizer(
                delegate = mockk(),
                additionalPatterns = additional
            )
            val expected = RedactedToolResponseSummarizer.DEFAULT_PATTERNS.size + 2
            assertEquals(expected, redacted.patternCount()) { "기본 + 2" }
        }
    }

    @Nested
    inner class CustomReplacement {

        @Test
        fun `사용자 정의 replacement가 적용되어야 한다`() {
            val delegate = staticDelegate(text = "email user@a.com")
            val redacted = RedactedToolResponseSummarizer(
                delegate = delegate,
                replacement = "<MASKED>"
            )
            val result = redacted.summarize("tool", "", true)

            assertTrue(result!!.text.contains("<MASKED>"))
            assertFalse(result.text.contains("***"))
        }
    }

    @Nested
    inner class ChainIntegration {

        @Test
        fun `ChainedToolResponseSummarizer를 감싸도 정상 동작해야 한다`() {
            val chain = ChainedToolResponseSummarizer(
                NoOpToolResponseSummarizer,
                DefaultToolResponseSummarizer()
            )
            val redacted = RedactedToolResponseSummarizer(chain)

            // Default가 text: "issues: 1건 [user@company.com]" 같은 결과를 만들 수도 있음
            // 우리는 primaryKey로 이메일이 들어가는 케이스를 시뮬레이션
            val payload = """[{"title":"admin@company.com 담당 이슈"}]"""
            val result = redacted.summarize("jira_search", payload, true)
            assertNotNull(result) { "체인 결과가 있어야 한다" }
            assertEquals(SummaryKind.LIST_TOP_N, result!!.kind)

            val allText = "${result.text} ${result.primaryKey}"
            assertFalse(allText.contains("admin@company.com")) {
                "체인 결과의 이메일이 마스킹되어야 한다: $allText"
            }
        }

        @Test
        fun `빈 체인이 null을 반환하면 데코레이터도 null이어야 한다`() {
            val chain = ChainedToolResponseSummarizer(emptyList())
            val redacted = RedactedToolResponseSummarizer(chain)

            assertNull(redacted.summarize("tool", "", true))
        }
    }

    @Nested
    inner class DefaultSummarizerIntegration {

        @Test
        fun `Default summarizer가 추출한 이메일 식별자가 마스킹되어야 한다`() {
            val default = DefaultToolResponseSummarizer()
            val redacted = RedactedToolResponseSummarizer(default)

            // title 필드가 IDENTIFIER_FIELDS에 포함되므로 primaryKey로 추출됨
            val payload = """[{"title":"user@company.com 이슈"}]"""
            val result = redacted.summarize("jira_search", payload, true)

            assertNotNull(result) { "결과가 있어야 한다" }
            assertEquals(SummaryKind.LIST_TOP_N, result!!.kind)
            // primaryKey에 이메일이 들어있을 수 있으므로 마스킹 확인
            val primaryKey = result.primaryKey
            if (primaryKey != null) {
                assertFalse(primaryKey.contains("user@company.com")) {
                    "primaryKey의 이메일이 마스킹되어야 한다: $primaryKey"
                }
            }
            // text에도 이메일이 포함되지 않아야 함
            assertFalse(result.text.contains("user@company.com")) {
                "text의 이메일이 마스킹되어야 한다: ${result.text}"
            }
        }

        @Test
        fun `이슈 키처럼 PII가 아닌 식별자는 그대로 유지되어야 한다`() {
            val default = DefaultToolResponseSummarizer()
            val redacted = RedactedToolResponseSummarizer(default)

            val payload = """[{"key":"HRFW-5695"}]"""
            val result = redacted.summarize("jira_search", payload, true)

            assertNotNull(result)
            assertEquals("HRFW-5695", result!!.primaryKey) {
                "이슈 키는 PII가 아니므로 유지"
            }
        }
    }

    @Nested
    inner class PublicConstants {

        @Test
        fun `DEFAULT_REPLACEMENT은 별표 세 개여야 한다`() {
            assertEquals("***", RedactedToolResponseSummarizer.DEFAULT_REPLACEMENT)
        }

        @Test
        fun `SAFE_FALLBACK은 REDACTED 표시여야 한다`() {
            assertEquals("[REDACTED]", RedactedToolResponseSummarizer.SAFE_FALLBACK)
        }

        @Test
        fun `DEFAULT_PATTERNS는 최소 7개여야 한다 (R228과 동일)`() {
            assertEquals(7, RedactedToolResponseSummarizer.DEFAULT_PATTERNS.size) {
                "이메일/Bearer/Atlassian/Slack/폰 국내/폰 국제/RRN = 7개"
            }
        }
    }
}

/**
 * 정적 요약을 반환하는 summarizer — 테스트용 fixture.
 */
private fun staticDelegate(
    text: String = "",
    kind: SummaryKind = SummaryKind.TEXT_FULL,
    originalLength: Int = 0,
    itemCount: Int? = null,
    primaryKey: String? = null
): ToolResponseSummarizer = object : ToolResponseSummarizer {
    override fun summarize(
        toolName: String,
        rawPayload: String,
        success: Boolean
    ): ToolResponseSummary = ToolResponseSummary(
        text = text,
        kind = kind,
        originalLength = originalLength,
        itemCount = itemCount,
        primaryKey = primaryKey
    )
}
