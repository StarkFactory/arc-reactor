package com.arc.reactor.controller

import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpServerRequestValidationTest {

    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `update request should reject description longer than 500 characters`() {
        val request = UpdateMcpServerRequest(description = "a".repeat(501))

        val violations = validator.validate(request)

        assertTrue(violations.any { it.propertyPath.toString() == "description" }) {
            "description over 500 chars must fail bean validation"
        }
    }

    @Test
    fun `update request should reject config larger than 20 entries`() {
        val oversizedConfig = (1..21).associate { index -> "key$index" to "value$index" }
        val request = UpdateMcpServerRequest(config = oversizedConfig)

        val violations = validator.validate(request)

        assertTrue(violations.any { it.propertyPath.toString() == "config" }) {
            "config with more than 20 entries must fail bean validation"
        }
    }
}
