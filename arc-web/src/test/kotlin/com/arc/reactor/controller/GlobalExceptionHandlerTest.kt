package com.arc.reactor.controller

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.HttpMethod
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.reactive.resource.NoResourceFoundException
import org.springframework.web.server.MethodNotAllowedException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebInputException

/**
 * GlobalExceptionHandler에 대한 테스트.
 *
 * 전역 예외 처리기의 동작을 검증합니다.
 */
class GlobalExceptionHandlerTest {

    private lateinit var handler: GlobalExceptionHandler

    @BeforeEach
    fun setup() {
        handler = GlobalExceptionHandler()
    }

    @Nested
    inner class ValidationErrors {

        @Test
        fun `field errors로 return 400해야 한다`() {
            val bindingResult = mockk<BindingResult>()
            every { bindingResult.fieldErrors } returns listOf(
                FieldError("request", "name", "must not be blank"),
                FieldError("request", "email", "must be a valid email")
            )
            val ex = WebExchangeBindException(mockk(relaxed = true), bindingResult)

            val response = handler.handleValidationErrors(ex)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
            val body = response.body!!
            assertEquals("요청 형식이 올바르지 않습니다", body.error) { "Error message should indicate validation failure" }
            val details = body.details!!
            assertEquals("must not be blank", details["name"]) { "Name error should match" }
            assertEquals("must be a valid email", details["email"]) { "Email error should match" }
            assertNotNull(body.timestamp) { "Should include timestamp" }
        }

        @Test
        fun `handle empty field errors해야 한다`() {
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
        fun `malformed input에 대해 return 400해야 한다`() {
            val ex = ServerWebInputException("Invalid JSON body")

            val response = handler.handleInputException(ex)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
            assertEquals("잘못된 요청입니다", response.body!!.error) { "내부 reason을 노출하지 않아야 한다" }
            assertNull(response.body!!.details) { "Should not have field details" }
        }
    }

    @Nested
    inner class NotFoundErrors {

        @Test
        fun `missing resources에 대해 return 404해야 한다`() {
            val ex = NoResourceFoundException("/api/approvals")

            val response = handler.handleNotFound(ex)

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
            assertEquals("요청한 리소스를 찾을 수 없습니다", response.body!!.error) { "Should have generic not found message" }
            assertNotNull(response.body!!.timestamp) { "Should include timestamp" }
        }
    }

    @Nested
    inner class ResponseStatusErrors {

        @Test
        fun `preserve explicit response status해야 한다`() {
            val ex = ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user context")

            val response = handler.handleResponseStatusException(ex)

            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode) { "Should preserve 401 UNAUTHORIZED" }
            assertEquals("인증이 필요합니다", response.body!!.error) {
                "내부 reason 대신 한글 마스킹 메시지를 반환해야 한다"
            }
            assertNotNull(response.body!!.timestamp) { "Should include timestamp" }
        }

        @Test
        fun `preserve framework method not allowed responses해야 한다`() {
            val ex = MethodNotAllowedException(HttpMethod.POST, setOf(HttpMethod.GET))

            val response = handler.handleResponseStatusException(ex)

            assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.statusCode) {
                "Should preserve 405 METHOD_NOT_ALLOWED"
            }
            assertEquals("요청을 처리할 수 없습니다", response.body!!.error) {
                "405는 한글 마스킹 메시지를 반환해야 한다"
            }
            assertFalse(response.body!!.error.contains("서버 오류가 발생했습니다")) {
                "405 errors must not be rewritten to generic 500 responses"
            }
        }
    }

    @Nested
    inner class IllegalArgumentErrors {

        @Test
        fun `illegal argument에 대해 return 400해야 한다`() {
            val ex = IllegalArgumentException("Invalid jobType 'UNKNOWN'")

            val response = handler.handleIllegalArgument(ex)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
            assertEquals("잘못된 요청입니다", response.body!!.error) {
                "내부 예외 메시지를 클라이언트에 노출하지 않아야 한다"
            }
            assertNotNull(response.body!!.timestamp) { "Should include timestamp" }
        }

        @Test
        fun `message is null일 때 return 400 with fallback message해야 한다`() {
            val ex = IllegalArgumentException()

            val response = handler.handleIllegalArgument(ex)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
            assertEquals("잘못된 요청입니다", response.body!!.error) {
                "메시지가 null이어도 마스킹된 응답을 반환해야 한다"
            }
        }
    }

    @Nested
    inner class IllegalStateErrors {

        @Test
        fun `illegal state에 대해 return 500해야 한다`() {
            val ex = IllegalStateException("Service not initialized")

            val response = handler.handleIllegalState(ex)

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode) { "Should return 500" }
            assertEquals("서버 오류가 발생했습니다", response.body!!.error) {
                "Should mask internal details for IllegalStateException"
            }
            assertNotNull(response.body!!.timestamp) { "Should include timestamp" }
        }

        @Test
        fun `not expose internal state details해야 한다`() {
            val ex = IllegalStateException("DB pool exhausted at com.arc.reactor.internal")

            val response = handler.handleIllegalState(ex)

            assertFalse(response.body!!.error.contains("DB pool")) {
                "Should NOT expose internal state details"
            }
        }
    }

    @Nested
    inner class GenericErrors {

        @Test
        fun `masked message로 return 500해야 한다`() {
            val ex = RuntimeException("DB password: secret123 connection failed")

            val response = handler.handleGenericException(ex)

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode) { "Should return 500" }
            assertEquals("서버 오류가 발생했습니다", response.body!!.error) { "Should mask internal details" }
            assertFalse(
                response.body!!.error.contains("secret123"),
                "Should NOT expose sensitive information"
            )
            assertNotNull(response.body!!.timestamp) { "Should include timestamp" }
        }

        @Test
        fun `not expose stack trace in response해야 한다`() {
            val ex = NullPointerException("at com.arc.reactor.internal.SomeClass.method(SomeClass.kt:42)")

            val response = handler.handleGenericException(ex)

            assertFalse(
                response.body!!.error.contains("SomeClass"),
                "Should NOT expose class names in error response"
            )
        }
    }
}
