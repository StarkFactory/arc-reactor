package com.arc.reactor.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [PiiPatterns] 공통 PII 정규식 단위 테스트.
 *
 * R233: R228/R231에서 추출된 공통 패턴의 의미론적 동작을 독립 검증한다.
 * 두 데코레이터 테스트와 독립적으로 패턴 정확성을 보장하는 회귀 방어선.
 */
class PiiPatternsTest {

    @Nested
    inner class DefaultList {

        @Test
        fun `DEFAULT 리스트는 7개 패턴을 포함해야 한다`() {
            assertEquals(7, PiiPatterns.DEFAULT.size) {
                "이메일/Bearer/Atlassian/Slack/폰(국내)/폰(국제)/RRN = 7개"
            }
        }

        @Test
        fun `DEFAULT 리스트의 순서는 상수 선언 순서와 일치해야 한다`() {
            assertEquals(PiiPatterns.EMAIL, PiiPatterns.DEFAULT[0]) { "첫 번째는 EMAIL" }
            assertEquals(PiiPatterns.BEARER_TOKEN, PiiPatterns.DEFAULT[1]) { "두 번째는 BEARER_TOKEN" }
            assertEquals(PiiPatterns.ATLASSIAN_GRANULAR, PiiPatterns.DEFAULT[2])
            assertEquals(PiiPatterns.SLACK_TOKEN, PiiPatterns.DEFAULT[3])
            assertEquals(PiiPatterns.KOREAN_PHONE_DOMESTIC, PiiPatterns.DEFAULT[4])
            assertEquals(PiiPatterns.KOREAN_PHONE_INTERNATIONAL, PiiPatterns.DEFAULT[5])
            assertEquals(PiiPatterns.KOREAN_RRN, PiiPatterns.DEFAULT[6])
        }
    }

    @Nested
    inner class EmailPattern {

        @Test
        fun `단순 이메일이 매칭되어야 한다`() {
            assertTrue(PiiPatterns.EMAIL.containsMatchIn("user@company.com"))
            assertTrue(PiiPatterns.EMAIL.containsMatchIn("alice@example.org"))
        }

        @Test
        fun `복합 이메일(점, 하이픈, plus)도 매칭되어야 한다`() {
            assertTrue(PiiPatterns.EMAIL.containsMatchIn("first.last-name+tag@sub.example-corp.io"))
            assertTrue(PiiPatterns.EMAIL.containsMatchIn("a.b.c@x.y.z"))
        }

        @Test
        fun `이메일이 아닌 문자열은 매칭되지 않아야 한다`() {
            assertFalse(PiiPatterns.EMAIL.containsMatchIn("not an email"))
            assertFalse(PiiPatterns.EMAIL.containsMatchIn("foo@"))
            assertFalse(PiiPatterns.EMAIL.containsMatchIn("@domain.com"))
        }

        @Test
        fun `여러 이메일이 한 문자열에 있으면 모두 매칭되어야 한다`() {
            val matches = PiiPatterns.EMAIL.findAll("from alice@a.com to bob@b.org cc carol@c.co.kr").toList()
            assertEquals(3, matches.size) { "3개 모두 매칭" }
        }
    }

    @Nested
    inner class BearerTokenPattern {

        @Test
        fun `대문자 Bearer JWT가 매칭되어야 한다`() {
            val text = "Authorization Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"
            assertTrue(PiiPatterns.BEARER_TOKEN.containsMatchIn(text))
        }

        @Test
        fun `소문자 bearer도 IGNORE_CASE로 매칭되어야 한다`() {
            assertTrue(PiiPatterns.BEARER_TOKEN.containsMatchIn("bearer abc123def"))
            assertTrue(PiiPatterns.BEARER_TOKEN.containsMatchIn("BEARER XYZ456"))
        }

        @Test
        fun `Bearer 다음 공백이 있어야 매칭된다`() {
            assertFalse(PiiPatterns.BEARER_TOKEN.containsMatchIn("Beareruserinfo")) {
                "공백 없는 Bearer 접두는 매칭 안 됨"
            }
        }
    }

    @Nested
    inner class AtlassianGranularPattern {

        @Test
        fun `Atlassian granular prefix가 매칭되어야 한다`() {
            assertTrue(
                PiiPatterns.ATLASSIAN_GRANULAR.containsMatchIn("ATATT3xFfGF0abcdefg1234567890")
            )
        }

        @Test
        fun `ATATT3xFfGF0이 아닌 prefix는 매칭되지 않아야 한다`() {
            assertFalse(PiiPatterns.ATLASSIAN_GRANULAR.containsMatchIn("ATATT4xFfGF0xyz"))
            assertFalse(PiiPatterns.ATLASSIAN_GRANULAR.containsMatchIn("ATATT3xFfGF"))
        }

        @Test
        fun `토큰 본문에 하이픈과 특수문자가 허용되어야 한다`() {
            assertTrue(
                PiiPatterns.ATLASSIAN_GRANULAR.containsMatchIn("ATATT3xFfGF0abc-def_ghi=")
            )
        }
    }

    @Nested
    inner class SlackTokenPattern {

        @Test
        fun `모든 Slack 토큰 유형이 매칭되어야 한다`() {
            assertTrue(PiiPatterns.SLACK_TOKEN.containsMatchIn("xoxb-bot-token"))
            assertTrue(PiiPatterns.SLACK_TOKEN.containsMatchIn("xoxp-user-token"))
            assertTrue(PiiPatterns.SLACK_TOKEN.containsMatchIn("xoxa-app-token"))
            assertTrue(PiiPatterns.SLACK_TOKEN.containsMatchIn("xoxr-refresh-token"))
            assertTrue(PiiPatterns.SLACK_TOKEN.containsMatchIn("xoxs-service-token"))
        }

        @Test
        fun `Slack 토큰이 아닌 문자열은 매칭되지 않아야 한다`() {
            assertFalse(PiiPatterns.SLACK_TOKEN.containsMatchIn("xoxz-unknown-type"))
            assertFalse(PiiPatterns.SLACK_TOKEN.containsMatchIn("xox-no-prefix"))
        }
    }

    @Nested
    inner class KoreanPhonePatterns {

        @Test
        fun `국내 휴대폰 모든 형식이 매칭되어야 한다`() {
            assertTrue(PiiPatterns.KOREAN_PHONE_DOMESTIC.containsMatchIn("010-1234-5678"))
            assertTrue(PiiPatterns.KOREAN_PHONE_DOMESTIC.containsMatchIn("011-234-5678"))
            assertTrue(PiiPatterns.KOREAN_PHONE_DOMESTIC.containsMatchIn("019-9999-0000"))
        }

        @Test
        fun `국내 휴대폰 하이픈 없으면 매칭되지 않아야 한다`() {
            assertFalse(PiiPatterns.KOREAN_PHONE_DOMESTIC.containsMatchIn("01012345678"))
        }

        @Test
        fun `국제 표기 휴대폰이 매칭되어야 한다`() {
            assertTrue(
                PiiPatterns.KOREAN_PHONE_INTERNATIONAL.containsMatchIn("+82-10-1234-5678")
            )
            assertTrue(
                PiiPatterns.KOREAN_PHONE_INTERNATIONAL.containsMatchIn("82-10-1234-5678")
            )
        }

        @Test
        fun `KT-10 같은 변형은 매칭되지 않아야 한다`() {
            assertFalse(
                PiiPatterns.KOREAN_PHONE_INTERNATIONAL.containsMatchIn("+KT-10-1234-5678")
            )
        }
    }

    @Nested
    inner class KoreanRrnPattern {

        @Test
        fun `정상 주민번호 형식이 매칭되어야 한다`() {
            assertTrue(PiiPatterns.KOREAN_RRN.containsMatchIn("900101-1234567")) { "남자 1900년대" }
            assertTrue(PiiPatterns.KOREAN_RRN.containsMatchIn("900101-2345678")) { "여자 1900년대" }
            assertTrue(PiiPatterns.KOREAN_RRN.containsMatchIn("200101-3456789")) { "남자 2000년대" }
            assertTrue(PiiPatterns.KOREAN_RRN.containsMatchIn("200101-4567890")) { "여자 2000년대" }
        }

        @Test
        fun `첫 자리가 5 이상이면 매칭되지 않아야 한다 (오탐 방지)`() {
            assertFalse(PiiPatterns.KOREAN_RRN.containsMatchIn("900101-5999999"))
            assertFalse(PiiPatterns.KOREAN_RRN.containsMatchIn("900101-9999999"))
        }

        @Test
        fun `하이픈 없는 번호는 매칭되지 않아야 한다`() {
            assertFalse(PiiPatterns.KOREAN_RRN.containsMatchIn("9001011234567"))
        }

        @Test
        fun `자리 수가 부족하면 매칭되지 않아야 한다`() {
            assertFalse(PiiPatterns.KOREAN_RRN.containsMatchIn("900101-123"))
            assertFalse(PiiPatterns.KOREAN_RRN.containsMatchIn("9001-1234567"))
        }
    }

    @Nested
    inner class ReplacementBehavior {

        @Test
        fun `이메일 패턴으로 replace 시 매칭 부분만 대체되어야 한다`() {
            val result = PiiPatterns.EMAIL.replace("Hello alice@company.com !", "***")
            assertEquals("Hello *** !", result)
        }

        @Test
        fun `여러 패턴을 순차 적용하면 모두 대체되어야 한다`() {
            var text = "이메일 alice@a.com 전화 010-1234-5678"
            for (pattern in PiiPatterns.DEFAULT) {
                text = pattern.replace(text, "***")
            }
            assertFalse(text.contains("alice@a.com"))
            assertFalse(text.contains("010-1234-5678"))
            assertTrue(text.contains("이메일"))
            assertTrue(text.contains("전화"))
        }

        @Test
        fun `매칭되지 않는 문자열은 변경되지 않아야 한다`() {
            val original = "이 문장에는 PII가 없습니다."
            var text = original
            for (pattern in PiiPatterns.DEFAULT) {
                text = pattern.replace(text, "***")
            }
            assertEquals(original, text) { "원본이 그대로 유지되어야 한다" }
        }
    }

    @Nested
    inner class SharedReferenceInvariance {

        @Test
        fun `R228 RedactedApprovalContextResolver와 동일한 리스트를 참조해야 한다`() {
            assertEquals(
                PiiPatterns.DEFAULT,
                com.arc.reactor.approval.RedactedApprovalContextResolver.DEFAULT_PATTERNS
            ) { "R228과 동일 리스트" }
        }

        @Test
        fun `R231 RedactedToolResponseSummarizer와 동일한 리스트를 참조해야 한다`() {
            assertEquals(
                PiiPatterns.DEFAULT,
                com.arc.reactor.tool.summarize.RedactedToolResponseSummarizer.DEFAULT_PATTERNS
            ) { "R231과 동일 리스트" }
        }

        @Test
        fun `R228과 R231의 리스트는 동일 인스턴스여야 한다 (single source of truth)`() {
            assertTrue(
                com.arc.reactor.approval.RedactedApprovalContextResolver.DEFAULT_PATTERNS ===
                    com.arc.reactor.tool.summarize.RedactedToolResponseSummarizer.DEFAULT_PATTERNS
            ) {
                "두 데코레이터는 PiiPatterns.DEFAULT의 동일 인스턴스를 공유해야 한다 (reference equality)"
            }
        }
    }
}
