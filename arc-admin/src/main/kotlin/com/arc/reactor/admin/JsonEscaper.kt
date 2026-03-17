package com.arc.reactor.admin

/**
 * Jackson 의존성 없이 최소한의 JSON 이스케이프를 수행하는 유틸리티.
 *
 * MCP 서버(McpMetricReporter)와 TimescaleSpanExporter에서 중복되던 로직을 통합한다.
 */
object JsonEscaper {

    /** JSON 문자열 이스케이프. 백슬래시, 따옴표, 제어 문자를 처리한다. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '"' -> sb.append("\\\"")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code in 0x00..0x1F -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 값을 JSON 문자열로 변환한다. null은 "null", String은 따옴표 포함. */
    fun valueToJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escape(value)}\""
        is Boolean, is Number -> value.toString()
        else -> "\"${escape(value.toString())}\""
    }

    /** Map을 JSON 객체 문자열로 직렬화한다. */
    fun mapToJson(map: Map<String, Any?>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            "\"${escape(k)}\":${valueToJson(v)}"
        }
        return "{$entries}"
    }
}
