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
        assertTrue(validator.isValidFormat("""{"name":"arc"}""", ResponseFormat.JSON))
        assertTrue(validator.isValidFormat("name: arc", ResponseFormat.YAML))
        assertFalse(validator.isValidFormat("{bad", ResponseFormat.JSON))
        assertFalse(validator.isValidFormat(": bad", ResponseFormat.YAML))
    }
}
