package com.arc.reactor.controller

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ServerWebInputException

class GlobalExceptionHandlerTest {

    private lateinit var handler: GlobalExceptionHandler

    @BeforeEach
    fun setup() {
        handler = GlobalExceptionHandler()
    }

    @Nested
    inner class ValidationErrors {

        @Test
        fun `should return 400 with field errors`() {
            val bindingResult = mockk<BindingResult>()
            every { bindingResult.fieldErrors } returns listOf(
                FieldError("request", "name", "must not be blank"),
                FieldError("request", "email", "must be a valid email")
            )
            val ex = WebExchangeBindException(mockk(relaxed = true), bindingResult)

            val response = handler.handleValidationErrors(ex)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
            val body = response.body!!
            assertEquals("Validation failed", body.error) { "Error message should indicate validation failure" }
            val details = body.details!!
            assertEquals("must not be blank", details["name"]) { "Name error should match" }
            assertEquals("must be a valid email", details["email"]) { "Email error should match" }
            assertNotNull(body.timestamp) { "Should include timestamp" }
        }

        @Test
        fun `should handle empty field errors`() {
            val bindingResult = mockk<BindingResult>()
            every { bindingResult.fieldErrors } returns emptyList()
            val ex = WebExchangeBindException(mockk(relaxed = true), bindingResult)

            val response = handler.handleValidationErrors(ex)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should still return 400" }
            assertTrue(response.body!!.details!!.isEmpty()) { "Details should be empty for no field errors" }
        }
    }

    @Nested
    inner class InputErrors {

        @Test
        fun `should return 400 for malformed input`() {
            val ex = ServerWebInputException("Invalid JSON body")

            val response = handler.handleInputException(ex)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
            assertTrue(response.body!!.error.contains("Invalid JSON body")) { "Should include reason" }
            assertNull(response.body!!.details) { "Should not have field details" }
        }
    }

    @Nested
    inner class GenericErrors {

        @Test
        fun `should return 500 with masked message`() {
            val ex = RuntimeException("DB password: secret123 connection failed")

            val response = handler.handleGenericException(ex)

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode) { "Should return 500" }
            assertEquals("Internal server error", response.body!!.error) { "Should mask internal details" }
            assertFalse(
                response.body!!.error.contains("secret123"),
                "Should NOT expose sensitive information"
            )
            assertNotNull(response.body!!.timestamp) { "Should include timestamp" }
        }

        @Test
        fun `should not expose stack trace in response`() {
            val ex = NullPointerException("at com.arc.reactor.internal.SomeClass.method(SomeClass.kt:42)")

            val response = handler.handleGenericException(ex)

            assertFalse(
                response.body!!.error.contains("SomeClass"),
                "Should NOT expose class names in error response"
            )
        }
    }
}
