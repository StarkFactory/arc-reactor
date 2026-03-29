package com.arc.reactor.slack.tools.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [toJson] 및 [errorJson] 유틸리티 함수 테스트.
 *
 * JSON 직렬화가 올바른 형식을 생성하는지, 그리고 에러 래퍼가
 * `{"error": "..."}` 구조를 일관되게 반환하는지 검증한다.
 */
class ToolResponseTest {

    @Nested
    inner class ToJsonTest {

        @Test
        fun `단순 데이터 클래스를 JSON 문자열로 직렬화한다`() {
            data class Sample(val id: String, val count: Int)
            val result = toJson(Sample("abc", 3))
            assertEquals("""{"id":"abc","count":3}""", result) {
                "단순 data class는 필드명-값 쌍으로 직렬화되어야 한다"
            }
        }

        @Test
        fun `Map을 JSON 객체로 직렬화한다`() {
            val result = toJson(mapOf("channel" to "C123", "ts" to "1234.5678"))
            assertTrue(result.contains("\"channel\":\"C123\"")) {
                "Map의 channel 키가 JSON에 포함되어야 한다"
            }
            assertTrue(result.contains("\"ts\":\"1234.5678\"")) {
                "Map의 ts 키가 JSON에 포함되어야 한다"
            }
        }

        @Test
        fun `빈 Map을 빈 JSON 객체로 직렬화한다`() {
            val result = toJson(emptyMap<String, Any>())
            assertEquals("{}", result) {
                "빈 Map은 빈 JSON 객체 {}로 직렬화되어야 한다"
            }
        }

        @Test
        fun `List를 JSON 배열로 직렬화한다`() {
            val result = toJson(listOf("a", "b", "c"))
            assertEquals("""["a","b","c"]""", result) {
                "문자열 리스트는 JSON 배열로 직렬화되어야 한다"
            }
        }

        @Test
        fun `null 값을 포함한 Map을 올바르게 직렬화한다`() {
            val result = toJson(mapOf("key" to null))
            assertEquals("""{"key":null}""", result) {
                "null 값은 JSON null로 직렬화되어야 한다"
            }
        }

        @Test
        fun `중첩 객체를 재귀적으로 직렬화한다`() {
            val result = toJson(mapOf("outer" to mapOf("inner" to 42)))
            assertEquals("""{"outer":{"inner":42}}""", result) {
                "중첩 객체는 재귀적으로 JSON으로 변환되어야 한다"
            }
        }

        @Test
        fun `특수문자를 포함한 문자열을 이스케이프한다`() {
            val result = toJson(mapOf("msg" to "line1\nline2"))
            assertTrue(result.contains("\\n")) {
                "줄바꿈 문자는 JSON 이스케이프 시퀀스로 변환되어야 한다"
            }
        }
    }

    @Nested
    inner class ErrorJsonTest {

        @Test
        fun `에러 메시지를 error 키를 가진 JSON 객체로 반환한다`() {
            val result = errorJson("channel_not_found")
            assertEquals("""{"error":"channel_not_found"}""", result) {
                "에러 메시지는 {\"error\":\"...\"} 형식으로 래핑되어야 한다"
            }
        }

        @Test
        fun `빈 문자열 에러 메시지도 올바른 JSON으로 반환한다`() {
            val result = errorJson("")
            assertEquals("""{"error":""}""", result) {
                "빈 에러 메시지도 error 키를 가진 JSON 객체로 반환되어야 한다"
            }
        }

        @Test
        fun `공백을 포함한 에러 메시지를 올바르게 직렬화한다`() {
            val result = errorJson("not authorized to access channel")
            assertEquals("""{"error":"not authorized to access channel"}""", result) {
                "공백이 포함된 에러 메시지도 그대로 직렬화되어야 한다"
            }
        }

        @Test
        fun `특수문자를 포함한 에러 메시지를 이스케이프한다`() {
            val result = errorJson("error: \"quota\" exceeded")
            assertTrue(result.startsWith("{\"error\":")) {
                "errorJson 결과는 항상 error 키로 시작해야 한다"
            }
            assertTrue(result.contains("\\\"quota\\\"")) {
                "큰따옴표는 JSON 이스케이프 시퀀스로 변환되어야 한다"
            }
        }

        @Test
        fun `errorJson 반환값은 toJson 결과와 동일하다`() {
            val message = "rate_limited"
            val fromErrorJson = errorJson(message)
            val fromToJson = toJson(mapOf("error" to message))
            assertEquals(fromToJson, fromErrorJson) {
                "errorJson은 toJson(mapOf(\"error\" to message))와 동일한 결과를 반환해야 한다"
            }
        }
    }
}
