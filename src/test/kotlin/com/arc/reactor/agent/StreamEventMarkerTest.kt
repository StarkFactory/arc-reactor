package com.arc.reactor.agent

import com.arc.reactor.agent.model.StreamEventMarker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [StreamEventMarker] parsing and generation.
 *
 * Covers: tool_start, tool_end, error markers, and non-marker text.
 */
class StreamEventMarkerTest {

    @Nested
    inner class ToolStartMarker {

        @Test
        fun `should generate and parse tool_start marker`() {
            val marker = StreamEventMarker.toolStart("calculator")
            val parsed = StreamEventMarker.parse(marker)

            assertNotNull(parsed) { "tool_start marker should be parseable" }
            assertEquals("tool_start", parsed!!.first) { "Event type should be tool_start" }
            assertEquals("calculator", parsed.second) { "Payload should be tool name" }
        }

        @Test
        fun `should detect tool_start as marker`() {
            val marker = StreamEventMarker.toolStart("my-tool")
            assertTrue(StreamEventMarker.isMarker(marker)) { "tool_start should be detected as marker" }
        }
    }

    @Nested
    inner class ToolEndMarker {

        @Test
        fun `should generate and parse tool_end marker`() {
            val marker = StreamEventMarker.toolEnd("calculator")
            val parsed = StreamEventMarker.parse(marker)

            assertNotNull(parsed) { "tool_end marker should be parseable" }
            assertEquals("tool_end", parsed!!.first) { "Event type should be tool_end" }
            assertEquals("calculator", parsed.second) { "Payload should be tool name" }
        }

        @Test
        fun `should detect tool_end as marker`() {
            val marker = StreamEventMarker.toolEnd("my-tool")
            assertTrue(StreamEventMarker.isMarker(marker)) { "tool_end should be detected as marker" }
        }
    }

    @Nested
    inner class ErrorMarker {

        @Test
        fun `should generate and parse error marker`() {
            val marker = StreamEventMarker.error("Request timed out.")
            val parsed = StreamEventMarker.parse(marker)

            assertNotNull(parsed) { "error marker should be parseable" }
            assertEquals("error", parsed!!.first) { "Event type should be error" }
            assertEquals("Request timed out.", parsed.second) { "Payload should be error message" }
        }

        @Test
        fun `should detect error as marker`() {
            val marker = StreamEventMarker.error("Something went wrong")
            assertTrue(StreamEventMarker.isMarker(marker)) { "error should be detected as marker" }
        }

        @Test
        fun `should handle empty error message`() {
            val marker = StreamEventMarker.error("")
            val parsed = StreamEventMarker.parse(marker)

            assertNotNull(parsed) { "Empty error marker should be parseable" }
            assertEquals("error", parsed!!.first) { "Event type should be error" }
            assertEquals("", parsed.second) { "Payload should be empty string" }
        }

        @Test
        fun `should preserve special characters in error message`() {
            val message = "Error: context length exceeded (128000 tokens > max)"
            val marker = StreamEventMarker.error(message)
            val parsed = StreamEventMarker.parse(marker)

            assertNotNull(parsed) { "Error with special chars should be parseable" }
            assertEquals(message, parsed!!.second) { "Special characters should be preserved" }
        }
    }

    @Nested
    inner class NonMarkerText {

        @Test
        fun `should return null for plain text`() {
            assertNull(StreamEventMarker.parse("Hello World")) {
                "Plain text should not be parsed as marker"
            }
        }

        @Test
        fun `should return false for plain text isMarker check`() {
            assertFalse(StreamEventMarker.isMarker("Just text")) {
                "Plain text should not be detected as marker"
            }
        }

        @Test
        fun `should return null for text containing error keyword`() {
            assertNull(StreamEventMarker.parse("[error] old format")) {
                "Old [error] format should not be parsed as marker"
            }
        }
    }
}
