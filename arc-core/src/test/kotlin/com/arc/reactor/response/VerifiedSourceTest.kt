package com.arc.reactor.response

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * VerifiedSource 모델에 대한 테스트.
 *
 * 검증된 출처 데이터 모델의 동작을 검증합니다.
 */
class VerifiedSourceTest {

    @Test
    fun `extract nested self href urls해야 한다`() {
        val output = """
            {
              "issues": [
                {
                  "_links": {
                    "self": {
                      "href": "https://example.atlassian.net/rest/api/3/issue/10000"
                    }
                  },
                  "key": "DEV-1"
                }
              ]
            }
        """.trimIndent()

        val sources = VerifiedSourceExtractor.extract("jira_my_open_issues", output)

        assertEquals(1, sources.size) { "Nested self href URLs should be extracted as verified sources" }
        assertEquals("10000", sources.first().title) { "Title should be inferred from URL segment" }
        assertEquals("https://example.atlassian.net/rest/api/3/issue/10000", sources.first().url) {
            "Extracted URL should remain intact"
        }
        assertTrue(sources.first().toolName == "jira_my_open_issues") {
            "toolName should be preserved from extractor input"
        }
    }

    @Test
    fun `not extract attachment download urls해야 한다`() {
        val output = """
            {
              "content": {
                "webUrl": "https://example.atlassian.net/download/attachments/123/secret.pdf"
              }
            }
        """.trimIndent()

        val sources = VerifiedSourceExtractor.extract("confluence_answer_question", output)

        assertEquals(0, sources.size) {
            "Attachment URLs should not be returned as verified sources"
        }
    }

    @Test
    fun `api paths로 extract openapi and api urls even해야 한다`() {
        val output = """
            {
              "webUrl": "https://petstore3.swagger.io/api/v3/openapi.json",
              "specUrl": "https://petstore3.swagger.io/api/v3/openapi.json"
            }
        """.trimIndent()

        val sources = VerifiedSourceExtractor.extract("spec_detail", output)

        assertEquals(1, sources.size) {
            "API urls should be accepted for spec tools"
        }
        assertEquals("openapi.json", sources.first().title) {
            "Title should be inferred from URL segment when no title field exists"
        }
    }

    // ── R192~R194: toolInsights 추출 (extractInsights) ──

    @Test
    fun `extractInsights should read top-level insights array`() {
        val output = """
            {
              "ok": true,
              "insights": [
                "총 12건",
                "24시간+ 미업데이트: 5건",
                "평균 수명: 4.3일"
              ]
            }
        """.trimIndent()

        val insights = VerifiedSourceExtractor.extractInsights(output)

        assertEquals(3, insights.size) { "Top-level insights array should be extracted" }
        assertEquals("총 12건", insights[0]) { "First insight should be preserved" }
        assertEquals("24시간+ 미업데이트: 5건", insights[1]) { "Second insight should be preserved" }
        assertEquals("평균 수명: 4.3일", insights[2]) { "Third insight should be preserved" }
    }

    @Test
    fun `extractInsights should return empty list for missing insights field`() {
        val output = """
            {
              "ok": true,
              "count": 0,
              "sources": []
            }
        """.trimIndent()

        val insights = VerifiedSourceExtractor.extractInsights(output)

        assertEquals(0, insights.size) { "No insights field → empty list" }
    }

    @Test
    fun `extractInsights should deduplicate and trim blank entries`() {
        val output = """
            {
              "insights": ["총 3건", " 총 3건 ", "", "  ", "마감 임박: 2건"]
            }
        """.trimIndent()

        val insights = VerifiedSourceExtractor.extractInsights(output)

        assertEquals(2, insights.size) { "Duplicates and blanks should be removed" }
        assertTrue(insights.contains("총 3건")) { "Trimmed duplicate should be preserved once" }
        assertTrue(insights.contains("마감 임박: 2건")) { "Non-blank distinct entry should be kept" }
    }

    @Test
    fun `extractInsights should cap to MAX_INSIGHTS`() {
        val manyInsights = (1..20).joinToString(",") { "\"item $it\"" }
        val output = """
            {
              "insights": [$manyInsights]
            }
        """.trimIndent()

        val insights = VerifiedSourceExtractor.extractInsights(output)

        assertTrue(insights.size <= 10) { "Should cap at MAX_INSIGHTS (10)" }
        assertEquals("item 1", insights.first()) { "Preservation order: first items kept" }
    }

    @Test
    fun `extractInsights should ignore non-string insight array entries`() {
        val output = """
            {
              "insights": ["유효", 42, null, {"nested": "object"}, "또 다른 유효"]
            }
        """.trimIndent()

        val insights = VerifiedSourceExtractor.extractInsights(output)

        assertEquals(2, insights.size) { "Only textual insights should be collected" }
        assertTrue(insights.contains("유효")) { "First textual entry should be kept" }
        assertTrue(insights.contains("또 다른 유효")) { "Last textual entry should be kept" }
    }

    @Test
    fun `extractInsights should handle malformed JSON gracefully`() {
        val output = "not valid json {"

        val insights = VerifiedSourceExtractor.extractInsights(output)

        assertEquals(0, insights.size) { "Malformed JSON → empty list without exception" }
    }
}
