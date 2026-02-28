package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructuredOutputValidatorTest {

    private val validator = StructuredOutputValidator()

    @Test
    fun `should strip markdown code fence`() {
        val raw = """
            ```json
            {"ok": true}
            ```
        """.trimIndent()

        assertEquals("""{"ok": true}""", validator.stripMarkdownCodeFence(raw))
    }

    @Test
    fun `should validate json and yaml`() {
        assertTrue(validator.isValidFormat("""{"name":"arc"}""", ResponseFormat.JSON), "Valid JSON object should pass validation")
        assertTrue(validator.isValidFormat("name: arc", ResponseFormat.YAML), "Valid YAML should pass validation")
        assertFalse(validator.isValidFormat("{bad", ResponseFormat.JSON), "Malformed JSON should fail validation")
        assertFalse(validator.isValidFormat(": bad", ResponseFormat.YAML), "Malformed YAML should fail validation")
    }
}
