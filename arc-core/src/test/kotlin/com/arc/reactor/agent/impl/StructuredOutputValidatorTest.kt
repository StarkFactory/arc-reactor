package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * StructuredOutputValidator에 대한 테스트.
 *
 * 구조화된 출력 검증 로직을 검증합니다.
 */
class StructuredOutputValidatorTest {

    private val validator = StructuredOutputValidator()

    @Test
    fun `strip markdown code fence해야 한다`() {
        val raw = """
            ```json
            {"ok": true}
            ```
        """.trimIndent()

        assertEquals("""{"ok": true}""", validator.stripMarkdownCodeFence(raw))
    }

    @Test
    fun `validate json and yaml해야 한다`() {
        assertTrue(validator.isValidFormat("""{"name":"arc"}""", ResponseFormat.JSON), "Valid JSON object should pass validation")
        assertTrue(validator.isValidFormat("name: arc", ResponseFormat.YAML), "Valid YAML should pass validation")
        assertFalse(validator.isValidFormat("{bad", ResponseFormat.JSON), "Malformed JSON should fail validation")
        assertFalse(validator.isValidFormat(": bad", ResponseFormat.YAML), "Malformed YAML should fail validation")
    }
}
