package com.arc.reactor.response

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.response.impl.SanitizedTextResponseFilter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [SanitizedTextResponseFilter] 테스트.
 *
 * 대상: [SANITIZED] 태그의 사용자 친화적 변환, 독립 행 제거, 무관 콘텐츠 통과.
 */
class SanitizedTextResponseFilterTest {

    private val filter = SanitizedTextResponseFilter()
    private val context = ResponseFilterContext(
        command = AgentCommand(systemPrompt = "Test", userPrompt = "배포 가이드 보여줘"),
        toolsUsed = listOf("confluence_search_by_text"),
        durationMs = 200
    )

    @Nested
    inner class InlineReplacement {

        @Test
        fun `인라인 SANITIZED 태그를 사용자 친화적 텍스트로 교체해야 한다`() = runTest {
            val content = "[SANITIZED] 가이드 (작성중)"
            val result = filter.filter(content, context)

            assertTrue(result.contains("(보안 처리됨)")) {
                "Should replace [SANITIZED] with user-friendly label, got: $result"
            }
            assertFalse(result.contains("[SANITIZED]")) {
                "Should not contain raw [SANITIZED] tag, got: $result"
            }
        }

        @Test
        fun `여러 SANITIZED 태그를 모두 교체해야 한다`() = runTest {
            val content = "제목: [SANITIZED] 배포 가이드\n내용: [SANITIZED] 참고 문서"
            val result = filter.filter(content, context)

            assertFalse(result.contains("[SANITIZED]")) {
                "All [SANITIZED] tags should be replaced, got: $result"
            }
            assertEquals(2, "(보안 처리됨)".toRegex().findAll(result).count()) {
                "Should have exactly 2 replacements"
            }
        }
    }

    @Nested
    inner class StandaloneLineRemoval {

        @Test
        fun `독립 행의 SANITIZED를 제거해야 한다`() = runTest {
            val content = "배포 가이드\n[SANITIZED]\n다음 단계"
            val result = filter.filter(content, context)

            assertFalse(result.contains("[SANITIZED]")) {
                "Standalone [SANITIZED] line should be removed, got: $result"
            }
            assertTrue(result.contains("배포 가이드")) {
                "Content before should be preserved"
            }
            assertTrue(result.contains("다음 단계")) {
                "Content after should be preserved"
            }
        }

        @Test
        fun `독립 행 제거 후 빈 줄을 정리해야 한다`() = runTest {
            val content = "시작\n\n[SANITIZED]\n\n\n끝"
            val result = filter.filter(content, context)

            assertFalse(result.contains("\n\n\n")) {
                "Should collapse triple+ newlines, got: $result"
            }
        }
    }

    @Nested
    inner class Passthrough {

        @Test
        fun `SANITIZED 태그가 없는 콘텐츠를 그대로 통과해야 한다`() = runTest {
            val content = "정상적인 배포 가이드 내용입니다."
            val result = filter.filter(content, context)

            assertEquals(content, result) {
                "Content without [SANITIZED] should pass through unchanged"
            }
        }

        @Test
        fun `빈 문자열을 그대로 통과해야 한다`() = runTest {
            val result = filter.filter("", context)

            assertEquals("", result) { "Empty content should remain empty" }
        }
    }

    @Nested
    inner class FilterOrder {

        @Test
        fun `MaxLength(10)보다 크고 VerifiedSources(90)보다 작은 order를 가져야 한다`() {
            assertTrue(filter.order > 10) {
                "Should run after MaxLengthResponseFilter(10), got: ${filter.order}"
            }
            assertTrue(filter.order < 90) {
                "Should run before VerifiedSourcesResponseFilter(90), got: ${filter.order}"
            }
        }
    }

    @Nested
    inner class Idempotency {

        @Test
        fun `두 번 적용해도 결과가 동일해야 한다`() = runTest {
            val content = "[SANITIZED] 가이드 (작성중)"
            val first = filter.filter(content, context)
            val second = filter.filter(first, context)

            assertEquals(first, second) {
                "Filter should be idempotent — second application should not change result"
            }
        }
    }
}
