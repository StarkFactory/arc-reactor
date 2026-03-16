package com.arc.reactor.agent

import com.arc.reactor.agent.model.StreamEventMarker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [StreamEventMarker] 파싱 및 생성에 대한 테스트.
 *
 * 대상: tool_start, tool_end, 에러 마커, 비마커 텍스트.
 */
class StreamEventMarkerTest {

    @Nested
    inner class ToolStartMarker {

        @Test
        fun `tool_start 마커를 생성하고 파싱해야 한다`() {
            val marker = StreamEventMarker.toolStart("calculator")
            val parsed = StreamEventMarker.parse(marker)

            assertNotNull(parsed) { "tool_start marker should be parseable" }
            assertEquals("tool_start", parsed!!.first) { "Event type should be tool_start" }
            assertEquals("calculator", parsed.second) { "Payload should be tool name" }
        }

        @Test
        fun `tool_start를 마커로 감지해야 한다`() {
            val marker = StreamEventMarker.toolStart("my-tool")
            assertTrue(StreamEventMarker.isMarker(marker)) { "tool_start should be detected as marker" }
        }
    }

    @Nested
    inner class ToolEndMarker {

        @Test
        fun `tool_end 마커를 생성하고 파싱해야 한다`() {
            val marker = StreamEventMarker.toolEnd("calculator")
            val parsed = StreamEventMarker.parse(marker)

            assertNotNull(parsed) { "tool_end marker should be parseable" }
            assertEquals("tool_end", parsed!!.first) { "Event type should be tool_end" }
            assertEquals("calculator", parsed.second) { "Payload should be tool name" }
        }

        @Test
        fun `tool_end를 마커로 감지해야 한다`() {
            val marker = StreamEventMarker.toolEnd("my-tool")
            assertTrue(StreamEventMarker.isMarker(marker)) { "tool_end should be detected as marker" }
        }
    }

    @Nested
    inner class ErrorMarker {

        @Test
        fun `error 마커를 생성하고 파싱해야 한다`() {
            val marker = StreamEventMarker.error("Request timed out.")
            val parsed = StreamEventMarker.parse(marker)

            assertNotNull(parsed) { "error marker should be parseable" }
            assertEquals("error", parsed!!.first) { "Event type should be error" }
            assertEquals("Request timed out.", parsed.second) { "Payload should be error message" }
        }

        @Test
        fun `error를 마커로 감지해야 한다`() {
            val marker = StreamEventMarker.error("Something went wrong")
            assertTrue(StreamEventMarker.isMarker(marker)) { "error should be detected as marker" }
        }

        @Test
        fun `빈 오류 메시지를 처리해야 한다`() {
            val marker = StreamEventMarker.error("")
            val parsed = StreamEventMarker.parse(marker)

            assertNotNull(parsed) { "Empty error marker should be parseable" }
            assertEquals("error", parsed!!.first) { "Event type should be error" }
            assertEquals("", parsed.second) { "Payload should be empty string" }
        }

        @Test
        fun `오류 메시지에서 특수 문자를 보존해야 한다`() {
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
        fun `일반 텍스트에 대해 null을 반환해야 한다`() {
            assertNull(StreamEventMarker.parse("Hello World")) {
                "Plain text should not be parsed as marker"
            }
        }

        @Test
        fun `일반 텍스트에 대해 isMarker가 false를 반환해야 한다`() {
            assertFalse(StreamEventMarker.isMarker("Just text")) {
                "Plain text should not be detected as marker"
            }
        }

        @Test
        fun `error 키워드를 포함한 텍스트에 대해 null을 반환해야 한다`() {
            assertNull(StreamEventMarker.parse("[error] old format")) {
                "Old [error] format should not be parsed as marker"
            }
        }
    }
}
