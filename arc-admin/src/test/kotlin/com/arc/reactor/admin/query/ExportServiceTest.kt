package com.arc.reactor.admin.query

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExportServiceTest {

    @Nested
    inner class EscapeCsv {

        @Test
        fun `returns empty string for null`() {
            ExportService.escapeCsv(null) shouldBe ""
        }

        @Test
        fun `returns value unchanged when no special chars`() {
            ExportService.escapeCsv("hello world") shouldBe "hello world"
        }

        @Test
        fun `wraps in quotes when value contains comma`() {
            ExportService.escapeCsv("hello,world") shouldBe "\"hello,world\""
        }

        @Test
        fun `wraps in quotes when value contains newline`() {
            ExportService.escapeCsv("line1\nline2") shouldBe "\"line1\nline2\""
        }

        @Test
        fun `wraps in quotes when value contains carriage return`() {
            ExportService.escapeCsv("line1\rline2") shouldBe "\"line1\rline2\""
        }

        @Test
        fun `escapes double quotes by doubling them`() {
            ExportService.escapeCsv("say \"hello\"") shouldBe "\"say \"\"hello\"\"\""
        }

        @Test
        fun `handles value with both commas and quotes`() {
            ExportService.escapeCsv("he said \"hi\", then left") shouldBe "\"he said \"\"hi\"\", then left\""
        }

        @Test
        fun `returns empty string unchanged`() {
            ExportService.escapeCsv("") shouldBe ""
        }

        @Test
        fun `handles single comma`() {
            ExportService.escapeCsv(",") shouldBe "\",\""
        }

        @Test
        fun `handles single quote`() {
            ExportService.escapeCsv("\"") shouldBe "\"\"\"\""
        }

        @Test
        fun `handles CRLF newline`() {
            ExportService.escapeCsv("a\r\nb") shouldBe "\"a\r\nb\""
        }

        @Test
        fun `no escaping for simple timestamps`() {
            ExportService.escapeCsv("2024-01-15T10:30:00Z") shouldBe "2024-01-15T10:30:00Z"
        }

        @Test
        fun `no escaping for UUIDs`() {
            ExportService.escapeCsv("550e8400-e29b-41d4-a716-446655440000") shouldBe "550e8400-e29b-41d4-a716-446655440000"
        }
    }
}
