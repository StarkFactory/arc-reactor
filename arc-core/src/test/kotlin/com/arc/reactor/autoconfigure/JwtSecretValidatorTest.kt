package com.arc.reactor.autoconfigure

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * JwtSecretValidator에 대한 테스트.
 *
 * JWT 비밀 키 유효성 검사를 검증합니다.
 */
class JwtSecretValidatorTest {

    @Nested
    inner class EmptySecret {

        @Test
        @Tag("regression")
        fun `auth enabled with empty JWT secret일 때 fail fast on startup해야 한다`() {
            val exception = assertThrows(IllegalStateException::class.java) {
                JwtSecretValidator("")
            }
            assertTrue(exception.message!!.contains("arc.reactor.auth.jwt-secret")) {
                "Error message should reference the property name, got: ${exception.message}"
            }
            assertTrue(exception.message!!.contains("openssl rand -base64 32")) {
                "Error message should include the generation command, got: ${exception.message}"
            }
        }
    }

    @Nested
    inner class ShortSecret {

        @Test
        fun `secret shorter than 32 bytes에 대해 throw IllegalStateException해야 한다`() {
            val exception = assertThrows(IllegalStateException::class.java) {
                JwtSecretValidator("too-short")
            }
            assertTrue(exception.message!!.contains("at least 32 characters")) {
                "Error message should state the minimum length, got: ${exception.message}"
            }
        }

        @Test
        @Tag("regression")
        fun `auth enabled with 31-byte JWT secret일 때 fail fast on startup해야 한다`() {
            assertThrows(IllegalStateException::class.java) {
                JwtSecretValidator("a".repeat(31))
            }
        }
    }

    @Nested
    inner class ValidSecret {

        @Test
        fun `secret of exactly 32 bytes에 대해 not throw해야 한다`() {
            assertDoesNotThrow({
                JwtSecretValidator("a".repeat(32))
            }) { "Validator should accept a 32-byte secret without throwing" }
        }

        @Test
        fun `secret longer than 32 bytes에 대해 not throw해야 한다`() {
            assertDoesNotThrow({
                JwtSecretValidator("arc-reactor-test-jwt-secret-key-at-least-32-chars-long")
            }) { "Validator should accept a long secret without throwing" }
        }
    }
}
