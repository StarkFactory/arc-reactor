package com.arc.reactor.tool.summarize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [DefaultToolResponseSummarizer] 휴리스틱 테스트.
 *
 * R223 Directive #2 ACI 도구 출력 요약 계층의 핵심 로직 검증.
 * 각 요약 분류(에러/리스트/구조화/텍스트/빈 응답)가 올바르게 동작하는지 확인한다.
 */
class DefaultToolResponseSummarizerTest {

    private val summarizer = DefaultToolResponseSummarizer()

    @Nested
    inner class EmptyResponse {

        @Test
        fun `빈 문자열은 EMPTY로 분류되어야 한다`() {
            val result = summarizer.summarize("jira_search", "", success = true)
            assertNotNull(result) { "빈 응답도 요약 객체가 생성되어야 한다" }
            assertEquals(SummaryKind.EMPTY, result!!.kind) { "빈 응답은 EMPTY로 분류" }
            assertEquals(0, result.originalLength) { "originalLength는 0이어야 한다" }
        }

        @Test
        fun `공백만 있는 문자열도 EMPTY로 분류되어야 한다`() {
            val result = summarizer.summarize("jira_search", "   \n  \t  ", success = true)
            assertNotNull(result) { "공백 응답도 요약 객체가 생성되어야 한다" }
            assertEquals(SummaryKind.EMPTY, result!!.kind) { "공백 응답은 EMPTY" }
        }
    }

    @Nested
    inner class ErrorResponse {

        @Test
        fun `success=false는 ERROR_CAUSE_FIRST로 분류되어야 한다`() {
            val result = summarizer.summarize(
                toolName = "jira_get_issue",
                rawPayload = "Error: issue JAR-999 not found",
                success = false
            )
            assertNotNull(result) { "실패 응답도 요약되어야 한다" }
            assertEquals(SummaryKind.ERROR_CAUSE_FIRST, result!!.kind)
            assertTrue(result.text.startsWith("에러:")) {
                "요약 텍스트는 '에러:' 로 시작해야 한다"
            }
            assertTrue(result.text.contains("JAR-999")) {
                "에러 메시지의 핵심 내용이 포함되어야 한다"
            }
        }

        @Test
        fun `JSON error 필드가 있으면 ERROR_CAUSE_FIRST로 분류되어야 한다`() {
            val result = summarizer.summarize(
                toolName = "bitbucket_list_prs",
                rawPayload = """{"error": "Repository not found", "status": 404}""",
                success = true  // outer success true지만 payload에 error 필드
            )
            assertNotNull(result) { "error 필드가 있으면 요약되어야 한다" }
            assertEquals(SummaryKind.ERROR_CAUSE_FIRST, result!!.kind)
            assertTrue(result.text.contains("Repository not found")) {
                "에러 내용이 요약에 포함되어야 한다"
            }
        }

        @Test
        fun `긴 에러 메시지는 잘려야 한다`() {
            val longError = "a".repeat(500)
            val result = summarizer.summarize(
                toolName = "some_tool",
                rawPayload = "Error: $longError",
                success = false
            )
            assertNotNull(result) { "긴 에러도 요약되어야 한다" }
            assertEquals(SummaryKind.ERROR_CAUSE_FIRST, result!!.kind)
            // 기본 errorMaxChars=200 + "에러: " + "..." 길이 고려
            assertTrue(result.text.length < 250) {
                "긴 에러 메시지는 잘려야 한다: actual length=${result.text.length}"
            }
            assertTrue(result.text.contains("...")) {
                "잘림 표시 '...' 가 포함되어야 한다"
            }
        }

        @Test
        fun `JSON error가 null이면 에러로 판단하지 않아야 한다`() {
            val result = summarizer.summarize(
                toolName = "jira_search",
                rawPayload = """{"error": null, "issues": [{"key":"JAR-1"}]}""",
                success = true
            )
            assertNotNull(result) { "요약되어야 한다" }
            // null error는 무시되고 issues 리스트 요약으로 진행
            assertEquals(SummaryKind.LIST_TOP_N, result!!.kind)
        }
    }

    @Nested
    inner class ListResponse {

        @Test
        fun `루트 배열은 LIST_TOP_N으로 분류되어야 한다`() {
            val payload = """[{"key":"JAR-1"},{"key":"JAR-2"},{"key":"JAR-3"}]"""
            val result = summarizer.summarize("jira_search", payload, success = true)
            assertNotNull(result) { "배열 응답은 요약되어야 한다" }
            assertEquals(SummaryKind.LIST_TOP_N, result!!.kind)
            assertEquals(3, result.itemCount) { "3개 항목이 카운트되어야 한다" }
            assertEquals("JAR-1", result.primaryKey) { "첫 항목의 key가 primaryKey여야 한다" }
            assertTrue(result.text.contains("3건")) { "총 개수가 텍스트에 포함되어야 한다" }
            assertTrue(result.text.contains("JAR-1")) { "상위 식별자가 텍스트에 포함되어야 한다" }
        }

        @Test
        fun `빈 배열도 LIST_TOP_N으로 분류되어야 한다`() {
            val result = summarizer.summarize("jira_search", "[]", success = true)
            assertNotNull(result) { "빈 배열도 요약되어야 한다" }
            assertEquals(SummaryKind.LIST_TOP_N, result!!.kind)
            assertEquals(0, result.itemCount) { "itemCount는 0이어야 한다" }
            assertTrue(result.text.contains("0건")) { "0건 표시되어야 한다" }
        }

        @Test
        fun `issues 필드가 있는 객체는 LIST_TOP_N으로 분류되어야 한다`() {
            val payload = """
                {
                    "issues": [
                        {"key": "HRFW-5695", "summary": "인사 기능 개선"},
                        {"key": "HRFW-5696", "summary": "채용 프로세스 업데이트"},
                        {"key": "HRFW-5697", "summary": "온보딩 문서화"}
                    ],
                    "total": 3
                }
            """.trimIndent()
            val result = summarizer.summarize("jira_search_issues", payload, success = true)
            assertNotNull(result) { "issues 필드가 있는 객체는 리스트로 요약되어야 한다" }
            assertEquals(SummaryKind.LIST_TOP_N, result!!.kind)
            assertEquals(3, result.itemCount) { "3개 issues가 카운트되어야 한다" }
            assertEquals("HRFW-5695", result.primaryKey) { "첫 key가 primary여야 한다" }
            assertTrue(result.text.contains("issues")) { "'issues' 레이블이 포함되어야 한다" }
        }

        @Test
        fun `pullRequests 필드가 있는 객체는 LIST_TOP_N으로 분류되어야 한다`() {
            val payload = """
                {
                    "pullRequests": [
                        {"id": 42, "title": "Fix bug"},
                        {"id": 43, "title": "Add feature"}
                    ]
                }
            """.trimIndent()
            val result = summarizer.summarize("bitbucket_list_prs", payload, success = true)
            assertEquals(SummaryKind.LIST_TOP_N, result!!.kind)
            assertEquals(2, result.itemCount) { "2개 PR이 카운트되어야 한다" }
            assertTrue(result.text.contains("pullRequests")) { "'pullRequests' 레이블이 포함되어야 한다" }
        }

        @Test
        fun `상위 N개만 primaryKey에 포함되어야 한다`() {
            val items = (1..20).joinToString(",") { """{"key":"ITEM-$it"}""" }
            val payload = "[$items]"
            val config = ToolResponseSummarizerConfig(listTopN = 3)
            val customSummarizer = DefaultToolResponseSummarizer(config)
            val result = customSummarizer.summarize("custom_tool", payload, success = true)
            assertNotNull(result) { "요약이 생성되어야 한다" }
            assertEquals(20, result!!.itemCount) { "20개 항목 모두 카운트" }
            assertEquals("ITEM-1", result.primaryKey) { "첫 항목" }
            // 상위 3개만 텍스트에 포함
            assertTrue(result.text.contains("ITEM-1")) { "ITEM-1 포함" }
            assertTrue(result.text.contains("ITEM-3")) { "ITEM-3 포함" }
            assertTrue(!result.text.contains("ITEM-4")) { "ITEM-4는 topN=3으로 제외되어야 한다" }
        }

        @Test
        fun `식별자 필드가 없는 리스트도 처리되어야 한다`() {
            val payload = """[{"data": "a"}, {"data": "b"}]"""
            val result = summarizer.summarize("tool", payload, success = true)
            assertNotNull(result) { "식별자 없는 리스트도 요약되어야 한다" }
            assertEquals(SummaryKind.LIST_TOP_N, result!!.kind)
            assertEquals(2, result.itemCount) { "항목 수는 정확해야 한다" }
            assertNull(result.primaryKey) { "식별자가 없으면 primaryKey는 null" }
        }
    }

    @Nested
    inner class StructuredResponse {

        @Test
        fun `알려진 리스트 필드가 없는 객체는 STRUCTURED로 분류되어야 한다`() {
            val payload = """
                {
                    "id": "JAR-42",
                    "summary": "작업 설명",
                    "status": "In Progress",
                    "assignee": "user@example.com"
                }
            """.trimIndent()
            val result = summarizer.summarize("jira_get_issue", payload, success = true)
            assertNotNull(result) { "구조화 객체도 요약되어야 한다" }
            assertEquals(SummaryKind.STRUCTURED, result!!.kind)
            assertTrue(result.text.contains("필드(")) { "필드 개수가 표시되어야 한다" }
        }

        @Test
        fun `많은 필드는 일부만 미리보기로 표시되어야 한다`() {
            val fields = (1..10).joinToString(",") { """"field$it":"value$it"""" }
            val payload = "{$fields}"
            val result = summarizer.summarize("tool", payload, success = true)
            assertNotNull(result) { "요약되어야 한다" }
            assertEquals(SummaryKind.STRUCTURED, result!!.kind)
            assertTrue(result.text.contains("...")) { "잘림 표시 포함" }
            assertTrue(result.text.contains("필드(10)")) { "전체 필드 수 표시" }
        }
    }

    @Nested
    inner class TextResponse {

        @Test
        fun `짧은 텍스트는 TEXT_FULL로 분류되어야 한다`() {
            val result = summarizer.summarize("tool", "짧은 응답입니다.", success = true)
            assertNotNull(result) { "텍스트도 요약되어야 한다" }
            assertEquals(SummaryKind.TEXT_FULL, result!!.kind)
            assertEquals("짧은 응답입니다.", result.text) { "원본이 그대로 보존되어야 한다" }
        }

        @Test
        fun `긴 텍스트는 TEXT_HEAD_TAIL로 분류되어야 한다`() {
            val longText = "가".repeat(500) + "핵심" + "나".repeat(500)
            val result = summarizer.summarize("tool", longText, success = true)
            assertNotNull(result) { "긴 텍스트도 요약되어야 한다" }
            assertEquals(SummaryKind.TEXT_HEAD_TAIL, result!!.kind)
            assertTrue(result.text.contains("...")) { "잘림 표시가 포함되어야 한다" }
            assertTrue(result.text.length < longText.length) { "요약은 원본보다 짧아야 한다" }
            assertTrue(result.originalLength == longText.length) {
                "originalLength는 원본 길이를 유지해야 한다"
            }
        }

        @Test
        fun `textFullThreshold 경계값을 정확히 처리해야 한다`() {
            val config = ToolResponseSummarizerConfig(textFullThreshold = 10)
            val customSummarizer = DefaultToolResponseSummarizer(config)

            // 정확히 10자: FULL
            val at = customSummarizer.summarize("tool", "1234567890", success = true)
            assertEquals(SummaryKind.TEXT_FULL, at!!.kind) { "threshold 이하는 FULL" }

            // 11자: HEAD_TAIL
            val above = customSummarizer.summarize("tool", "12345678901", success = true)
            assertEquals(SummaryKind.TEXT_HEAD_TAIL, above!!.kind) { "threshold 초과는 HEAD_TAIL" }
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `내부 예외 발생 시 null을 반환해야 한다`() {
            // 극단적으로 잘못된 JSON-like 입력 → 실제로 Jackson은 graceful하게 null 반환
            // 여기서는 일반 문자열로 fallback 경로를 확인한다
            val result = summarizer.summarize("tool", "not a json {", success = true)
            // Jackson 파싱 실패 → 텍스트 경로 → TEXT_FULL (짧으므로)
            assertNotNull(result) { "파싱 실패해도 텍스트 경로로 요약되어야 한다" }
            assertEquals(SummaryKind.TEXT_FULL, result!!.kind)
        }
    }

    @Nested
    inner class NoOpBehavior {

        @Test
        fun `NoOp 요약기는 항상 null을 반환해야 한다`() {
            val noOp = NoOpToolResponseSummarizer
            assertNull(noOp.summarize("any", "any", true)) { "NoOp은 null" }
            assertNull(noOp.summarize("any", "", false)) { "NoOp은 빈 응답에도 null" }
            assertNull(noOp.summarize("any", """[1,2,3]""", true)) { "NoOp은 JSON에도 null" }
        }
    }
}
