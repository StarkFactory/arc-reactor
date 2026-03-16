package com.arc.reactor.admin.query

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExportServiceTest {

    @Nested
    inner class EscapeCsv {

        @Test
        fun `null에 대해 empty string를 반환한다`() {
            ExportService.escapeCsv(null) shouldBe ""
        }

        @Test
        fun `no special chars일 때 value unchanged를 반환한다`() {
            ExportService.escapeCsv("hello world") shouldBe "hello world"
        }

        @Test
        fun `in quotes when value contains comma를 래핑한다`() {
            ExportService.escapeCsv("hello,world") shouldBe "\"hello,world\""
        }

        @Test
        fun `in quotes when value contains newline를 래핑한다`() {
            ExportService.escapeCsv("line1\nline2") shouldBe "\"line1\nline2\""
        }

        @Test
        fun `in quotes when value contains carriage return를 래핑한다`() {
            ExportService.escapeCsv("line1\rline2") shouldBe "\"line1\rline2\""
        }

        @Test
        fun `double quotes by doubling them를 이스케이프한다`() {
            ExportService.escapeCsv("say \"hello\"") shouldBe "\"say \"\"hello\"\"\""
        }

        @Test
        fun `value with both commas and quotes를 처리한다`() {
            ExportService.escapeCsv("he said \"hi\", then left") shouldBe "\"he said \"\"hi\"\", then left\""
        }

        @Test
        fun `empty string unchanged를 반환한다`() {
            ExportService.escapeCsv("") shouldBe ""
        }

        @Test
        fun `single comma를 처리한다`() {
            ExportService.escapeCsv(",") shouldBe "\",\""
        }

        @Test
        fun `single quote를 처리한다`() {
            ExportService.escapeCsv("\"") shouldBe "\"\"\"\""
        }

        @Test
        fun `CRLF newline를 처리한다`() {
            ExportService.escapeCsv("a\r\nb") shouldBe "\"a\r\nb\""
        }

        @Test
        fun `없는 escaping for simple timestamps`() {
            ExportService.escapeCsv("2024-01-15T10:30:00Z") shouldBe "2024-01-15T10:30:00Z"
        }

        @Test
        fun `없는 escaping for UUIDs`() {
            ExportService.escapeCsv("550e8400-e29b-41d4-a716-446655440000") shouldBe "550e8400-e29b-41d4-a716-446655440000"
        }
    }
}
