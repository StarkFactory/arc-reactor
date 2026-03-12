package com.arc.reactor.slack

import com.arc.reactor.slack.tools.tool.ToolInputValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolInputValidationTest {

    @Nested
    inner class NormalizeChannelId {

        @Test
        fun `accepts valid channel ID starting with C`() {
            assertEquals("C1234ABC", ToolInputValidation.normalizeChannelId("C1234ABC"),
                "Channel IDs starting with C should be accepted")
        }

        @Test
        fun `accepts valid channel ID starting with D (DM)`() {
            assertEquals("D0123456789", ToolInputValidation.normalizeChannelId("D0123456789"),
                "DM channel IDs starting with D should be accepted")
        }

        @Test
        fun `accepts valid channel ID starting with G (group)`() {
            assertEquals("G0123456789", ToolInputValidation.normalizeChannelId("G0123456789"),
                "Group channel IDs starting with G should be accepted")
        }

        @Test
        fun `rejects channel ID with invalid prefix`() {
            assertNull(ToolInputValidation.normalizeChannelId("X1234ABC"),
                "Channel IDs with invalid prefix should be rejected")
        }

        @Test
        fun `rejects channel ID with lowercase letters`() {
            assertNull(ToolInputValidation.normalizeChannelId("Cabc"),
                "Channel IDs with lowercase should be rejected")
        }

        @Test
        fun `trims whitespace before validation`() {
            assertEquals("C1234ABC", ToolInputValidation.normalizeChannelId("  C1234ABC  "),
                "Should trim whitespace before validating")
        }

        @Test
        fun `rejects blank input`() {
            assertNull(ToolInputValidation.normalizeChannelId("   "),
                "Blank input should return null")
        }

        @Test
        fun `rejects too-short channel ID`() {
            assertNull(ToolInputValidation.normalizeChannelId("CA"),
                "Channel IDs shorter than 3 chars should be rejected")
        }
    }

    @Nested
    inner class NormalizeUserId {

        @Test
        fun `accepts valid user ID starting with U`() {
            assertEquals("U0123ABC", ToolInputValidation.normalizeUserId("U0123ABC"),
                "User IDs starting with U should be accepted")
        }

        @Test
        fun `accepts valid user ID starting with W (enterprise)`() {
            assertEquals("W0123ABC", ToolInputValidation.normalizeUserId("W0123ABC"),
                "Enterprise user IDs starting with W should be accepted")
        }

        @Test
        fun `rejects user ID with invalid prefix`() {
            assertNull(ToolInputValidation.normalizeUserId("B0123ABC"),
                "User IDs with invalid prefix should be rejected")
        }
    }

    @Nested
    inner class NormalizeThreadTs {

        @Test
        fun `accepts valid thread timestamp`() {
            assertEquals("1234567890.123456", ToolInputValidation.normalizeThreadTs("1234567890.123456"),
                "Valid thread_ts should be accepted")
        }

        @Test
        fun `rejects timestamp without dot`() {
            assertNull(ToolInputValidation.normalizeThreadTs("1234567890"),
                "Timestamps without dot should be rejected")
        }

        @Test
        fun `rejects non-numeric timestamp`() {
            assertNull(ToolInputValidation.normalizeThreadTs("abc.def"),
                "Non-numeric timestamps should be rejected")
        }
    }

    @Nested
    inner class NormalizeEmoji {

        @Test
        fun `accepts plain emoji name`() {
            assertEquals("thumbsup", ToolInputValidation.normalizeEmoji("thumbsup"),
                "Plain emoji names should be accepted")
        }

        @Test
        fun `strips colon wrappers`() {
            assertEquals("thumbsup", ToolInputValidation.normalizeEmoji(":thumbsup:"),
                "Colon-wrapped emoji should have colons stripped")
        }

        @Test
        fun `accepts emoji with underscores and hyphens`() {
            assertEquals("face_with-tears", ToolInputValidation.normalizeEmoji("face_with-tears"),
                "Emoji with underscores and hyphens should be accepted")
        }

        @Test
        fun `rejects emoji with special characters`() {
            assertNull(ToolInputValidation.normalizeEmoji("test!@#"),
                "Emoji with special characters should be rejected")
        }
    }

    @Nested
    inner class NormalizeFilename {

        @Test
        fun `accepts valid filename`() {
            assertEquals("report.pdf", ToolInputValidation.normalizeFilename("report.pdf"),
                "Valid filenames should be accepted")
        }

        @Test
        fun `rejects filename with path separator`() {
            assertNull(ToolInputValidation.normalizeFilename("../etc/passwd"),
                "Filenames with path separators should be rejected")
        }

        @Test
        fun `rejects filename with backslash`() {
            assertNull(ToolInputValidation.normalizeFilename("test\\file"),
                "Filenames with backslash should be rejected")
        }

        @Test
        fun `rejects filename with null byte`() {
            assertNull(ToolInputValidation.normalizeFilename("test\u0000file"),
                "Filenames with null bytes should be rejected")
        }
    }

    @Nested
    inner class Sha256Hex {

        @Test
        fun `produces consistent 64-char hex digest`() {
            val hash = ToolInputValidation.sha256Hex("hello")
            assertNotNull(hash, "Hash should not be null")
            assertEquals(64, hash.length, "SHA-256 hex should be 64 characters")
        }

        @Test
        fun `produces same hash for same input`() {
            assertEquals(ToolInputValidation.sha256Hex("test"), ToolInputValidation.sha256Hex("test"),
                "Same input should produce same hash")
        }
    }
}
