package com.arc.reactor.response

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
