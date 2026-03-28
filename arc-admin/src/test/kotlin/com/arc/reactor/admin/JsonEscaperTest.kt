package com.arc.reactor.admin

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** [JsonEscaper]의 문자열 이스케이프, 값 직렬화, Map 직렬화 테스트 */
class JsonEscaperTest {

    @Nested
    inner class Escape {

        @Test
        fun `일반 문자열은 변경 없이 반환된다`() {
            assertEquals("hello world", JsonEscaper.escape("hello world")) {
                "일반 ASCII 문자열은 이스케이프 없이 그대로 반환되어야 한다"
            }
        }

        @Test
        fun `빈 문자열은 빈 문자열로 반환된다`() {
            assertEquals("", JsonEscaper.escape("")) {
                "빈 문자열은 빈 문자열로 반환되어야 한다"
            }
        }

        @Test
        fun `백슬래시는 이중 백슬래시로 이스케이프된다`() {
            assertEquals("C:\\\\Users\\\\test", JsonEscaper.escape("C:\\Users\\test")) {
                "백슬래시는 \\\\로 이스케이프되어야 한다"
            }
        }

        @Test
        fun `큰따옴표는 이스케이프된다`() {
            assertEquals("say \\\"hello\\\"", JsonEscaper.escape("say \"hello\"")) {
                "큰따옴표는 \\\"로 이스케이프되어야 한다"
            }
        }

        @Test
        fun `줄바꿈 문자는 이스케이프된다`() {
            assertEquals("line1\\nline2", JsonEscaper.escape("line1\nline2")) {
                "줄바꿈 문자는 \\n으로 이스케이프되어야 한다"
            }
        }

        @Test
        fun `캐리지 리턴은 이스케이프된다`() {
            assertEquals("line1\\rline2", JsonEscaper.escape("line1\rline2")) {
                "캐리지 리턴은 \\r로 이스케이프되어야 한다"
            }
        }

        @Test
        fun `탭 문자는 이스케이프된다`() {
            assertEquals("col1\\tcol2", JsonEscaper.escape("col1\tcol2")) {
                "탭 문자는 \\t로 이스케이프되어야 한다"
            }
        }

        @Test
        fun `0x01 제어 문자는 유니코드 이스케이프로 변환된다`() {
            assertEquals("\\u0001", JsonEscaper.escape("\u0001")) {
                "0x01 제어 문자는 \\u0001로 이스케이프되어야 한다"
            }
        }

        @Test
        fun `0x1F 제어 문자도 유니코드 이스케이프로 변환된다`() {
            assertEquals("\\u001f", JsonEscaper.escape("\u001F")) {
                "0x1F 제어 문자는 \\u001f로 이스케이프되어야 한다"
            }
        }

        @Test
        fun `한글 문자는 이스케이프 없이 반환된다`() {
            assertEquals("안녕하세요", JsonEscaper.escape("안녕하세요")) {
                "한글 등 0x1F 이상 유니코드 문자는 이스케이프 없이 반환되어야 한다"
            }
        }

        @Test
        fun `따옴표와 줄바꿈이 혼합된 문자열도 올바르게 처리된다`() {
            assertEquals("key: \\\"value\\\"\\nend", JsonEscaper.escape("key: \"value\"\nend")) {
                "따옴표와 줄바꿈이 혼합된 문자열도 모두 이스케이프되어야 한다"
            }
        }
    }

    @Nested
    inner class ValueToJson {

        @Test
        fun `null 값은 null 문자열로 직렬화된다`() {
            assertEquals("null", JsonEscaper.valueToJson(null)) {
                "null 값은 JSON null 리터럴로 직렬화되어야 한다"
            }
        }

        @Test
        fun `String 값은 큰따옴표로 감싸진다`() {
            assertEquals("\"hello\"", JsonEscaper.valueToJson("hello")) {
                "String 값은 큰따옴표로 감싸진 JSON 문자열로 직렬화되어야 한다"
            }
        }

        @Test
        fun `String 내부의 큰따옴표도 이스케이프된다`() {
            assertEquals("\"say \\\"hi\\\"\"", JsonEscaper.valueToJson("say \"hi\"")) {
                "String 내부의 큰따옴표도 이스케이프되어야 한다"
            }
        }

        @Test
        fun `Boolean true 값은 true로 직렬화된다`() {
            assertEquals("true", JsonEscaper.valueToJson(true)) {
                "Boolean true는 JSON true 리터럴로 직렬화되어야 한다"
            }
        }

        @Test
        fun `Boolean false 값은 false로 직렬화된다`() {
            assertEquals("false", JsonEscaper.valueToJson(false)) {
                "Boolean false는 JSON false 리터럴로 직렬화되어야 한다"
            }
        }

        @Test
        fun `Int 값은 숫자 그대로 직렬화된다`() {
            assertEquals("42", JsonEscaper.valueToJson(42)) {
                "Int 숫자 값은 따옴표 없이 직렬화되어야 한다"
            }
        }

        @Test
        fun `Double 값은 숫자 그대로 직렬화된다`() {
            assertEquals("3.14", JsonEscaper.valueToJson(3.14)) {
                "Double 숫자 값은 따옴표 없이 직렬화되어야 한다"
            }
        }

        @Test
        fun `Long 값은 숫자 그대로 직렬화된다`() {
            assertEquals("9999999999", JsonEscaper.valueToJson(9999999999L)) {
                "Long 숫자 값은 따옴표 없이 직렬화되어야 한다"
            }
        }

        @Test
        fun `기타 객체는 toString이 이스케이프되어 따옴표로 감싸진다`() {
            val result = JsonEscaper.valueToJson(listOf("a", "b"))
            assertTrue(result.startsWith("\"")) { "기타 객체는 따옴표로 시작해야 한다" }
            assertTrue(result.endsWith("\"")) { "기타 객체는 따옴표로 끝나야 한다" }
        }
    }

    @Nested
    inner class MapToJson {

        @Test
        fun `빈 Map은 빈 JSON 객체로 직렬화된다`() {
            assertEquals("{}", JsonEscaper.mapToJson(emptyMap())) {
                "빈 Map은 빈 JSON 객체로 직렬화되어야 한다"
            }
        }

        @Test
        fun `단일 항목 Map이 올바르게 직렬화된다`() {
            assertEquals("{\"key\":\"value\"}", JsonEscaper.mapToJson(mapOf("key" to "value"))) {
                "단일 항목 Map은 올바른 JSON 객체로 직렬화되어야 한다"
            }
        }

        @Test
        fun `숫자 값을 포함한 Map이 올바르게 직렬화된다`() {
            assertEquals("{\"count\":10}", JsonEscaper.mapToJson(mapOf("count" to 10))) {
                "숫자 값을 포함한 Map은 따옴표 없이 직렬화되어야 한다"
            }
        }

        @Test
        fun `null 값을 포함한 Map이 올바르게 직렬화된다`() {
            assertEquals("{\"key\":null}", JsonEscaper.mapToJson(mapOf("key" to null))) {
                "null 값을 포함한 Map은 null 리터럴로 직렬화되어야 한다"
            }
        }

        @Test
        fun `다중 항목 Map은 각 항목이 쉼표로 구분된다`() {
            // 순서 보장을 위해 LinkedHashMap 사용
            val map = linkedMapOf<String, Any?>("a" to 1, "b" to 2)
            assertEquals("{\"a\":1,\"b\":2}", JsonEscaper.mapToJson(map)) {
                "다중 항목 Map은 쉼표로 구분된 JSON 객체로 직렬화되어야 한다"
            }
        }

        @Test
        fun `키에 큰따옴표가 포함된 Map도 이스케이프된다`() {
            val result = JsonEscaper.mapToJson(mapOf("ke\"y" to "val"))
            assertTrue(result.contains("\\\"")) { "키에 포함된 큰따옴표도 이스케이프되어야 한다" }
        }

        @Test
        fun `결과는 항상 중괄호로 시작하고 끝난다`() {
            val result = JsonEscaper.mapToJson(mapOf("x" to true))
            assertTrue(result.startsWith("{")) { "JSON 객체는 중괄호로 시작해야 한다" }
            assertTrue(result.endsWith("}")) { "JSON 객체는 중괄호로 끝나야 한다" }
        }

        @Test
        fun `Boolean 값을 포함한 Map이 올바르게 직렬화된다`() {
            assertEquals("{\"active\":true}", JsonEscaper.mapToJson(mapOf("active" to true))) {
                "Boolean 값을 포함한 Map은 따옴표 없이 직렬화되어야 한다"
            }
        }
    }
}
