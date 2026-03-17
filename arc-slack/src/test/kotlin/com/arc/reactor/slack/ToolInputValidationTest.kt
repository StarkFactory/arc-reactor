package com.arc.reactor.slack

import com.arc.reactor.slack.tools.tool.ToolInputValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [ToolInputValidation]의 입력 정규화 및 검증 테스트.
 *
 * 채널 ID, 사용자 ID, 스레드 타임스탬프, 이모지, 파일명 등의
 * 정규화 로직과 유효하지 않은 입력 거부를 검증한다.
 */
class ToolInputValidationTest {

    @Nested
    inner class NormalizeChannelId {

        @Test
        fun `valid channel ID starting with C를 수락한다`() {
            assertEquals("C1234ABC", ToolInputValidation.normalizeChannelId("C1234ABC"),
                "Channel IDs starting with C should be accepted")
        }

        @Test
        fun `valid channel ID starting with D (DM)를 수락한다`() {
            assertEquals("D0123456789", ToolInputValidation.normalizeChannelId("D0123456789"),
                "DM channel IDs starting with D should be accepted")
        }

        @Test
        fun `valid channel ID starting with G (group)를 수락한다`() {
            assertEquals("G0123456789", ToolInputValidation.normalizeChannelId("G0123456789"),
                "Group channel IDs starting with G should be accepted")
        }

        @Test
        fun `channel ID with invalid prefix를 거부한다`() {
            assertNull(ToolInputValidation.normalizeChannelId("X1234ABC"),
                "Channel IDs with invalid prefix should be rejected")
        }

        @Test
        fun `channel ID with lowercase letters를 거부한다`() {
            assertNull(ToolInputValidation.normalizeChannelId("Cabc"),
                "Channel IDs with lowercase should be rejected")
        }

        @Test
        fun `whitespace before validation를 트리밍한다`() {
            assertEquals("C1234ABC", ToolInputValidation.normalizeChannelId("  C1234ABC  "),
                "Should trim whitespace before validating")
        }

        @Test
        fun `blank input를 거부한다`() {
            assertNull(ToolInputValidation.normalizeChannelId("   "),
                "Blank input should return null")
        }

        @Test
        fun `too-short channel ID를 거부한다`() {
            assertNull(ToolInputValidation.normalizeChannelId("CA"),
                "Channel IDs shorter than 3 chars should be rejected")
        }
    }

    @Nested
    inner class NormalizeUserId {

        @Test
        fun `valid user ID starting with U를 수락한다`() {
            assertEquals("U0123ABC", ToolInputValidation.normalizeUserId("U0123ABC"),
                "User IDs starting with U should be accepted")
        }

        @Test
        fun `valid user ID starting with W (enterprise)를 수락한다`() {
            assertEquals("W0123ABC", ToolInputValidation.normalizeUserId("W0123ABC"),
                "Enterprise user IDs starting with W should be accepted")
        }

        @Test
        fun `user ID with invalid prefix를 거부한다`() {
            assertNull(ToolInputValidation.normalizeUserId("B0123ABC"),
                "User IDs with invalid prefix should be rejected")
        }
    }

    @Nested
    inner class NormalizeThreadTs {

        @Test
        fun `valid thread timestamp를 수락한다`() {
            assertEquals("1234567890.123456", ToolInputValidation.normalizeThreadTs("1234567890.123456"),
                "Valid thread_ts should be accepted")
        }

        @Test
        fun `timestamp without dot를 거부한다`() {
            assertNull(ToolInputValidation.normalizeThreadTs("1234567890"),
                "Timestamps without dot should be rejected")
        }

        @Test
        fun `non-numeric timestamp를 거부한다`() {
            assertNull(ToolInputValidation.normalizeThreadTs("abc.def"),
                "Non-numeric timestamps should be rejected")
        }
    }

    @Nested
    inner class NormalizeEmoji {

        @Test
        fun `plain emoji name를 수락한다`() {
            assertEquals("thumbsup", ToolInputValidation.normalizeEmoji("thumbsup"),
                "Plain emoji names should be accepted")
        }

        @Test
        fun `colon wrappers를 제거한다`() {
            assertEquals("thumbsup", ToolInputValidation.normalizeEmoji(":thumbsup:"),
                "Colon-wrapped emoji should have colons stripped")
        }

        @Test
        fun `emoji with underscores and hyphens를 수락한다`() {
            assertEquals("face_with-tears", ToolInputValidation.normalizeEmoji("face_with-tears"),
                "Emoji with underscores and hyphens should be accepted")
        }

        @Test
        fun `emoji with special characters를 거부한다`() {
            assertNull(ToolInputValidation.normalizeEmoji("test!@#"),
                "Emoji with special characters should be rejected")
        }
    }

    @Nested
    inner class NormalizeFilename {

        @Test
        fun `valid filename를 수락한다`() {
            assertEquals("report.pdf", ToolInputValidation.normalizeFilename("report.pdf"),
                "Valid filenames should be accepted")
        }

        @Test
        fun `filename with path separator를 거부한다`() {
            assertNull(ToolInputValidation.normalizeFilename("../etc/passwd"),
                "Filenames with path separators should be rejected")
        }

        @Test
        fun `filename with backslash를 거부한다`() {
            assertNull(ToolInputValidation.normalizeFilename("test\\file"),
                "Filenames with backslash should be rejected")
        }

        @Test
        fun `filename with null byte를 거부한다`() {
            assertNull(ToolInputValidation.normalizeFilename("test\u0000file"),
                "Filenames with null bytes should be rejected")
        }
    }

    @Nested
    inner class Sha256Hex {

        @Test
        fun `consistent 64-char hex digest를 생성한다`() {
            val hash = ToolInputValidation.sha256Hex("hello")
            assertNotNull(hash, "Hash should not be null")
            assertEquals(64, hash.length, "SHA-256 hex should be 64 characters")
        }

        @Test
        fun `same hash for same input를 생성한다`() {
            assertEquals(ToolInputValidation.sha256Hex("test"), ToolInputValidation.sha256Hex("test"),
                "Same input should produce same hash")
        }
    }
}
