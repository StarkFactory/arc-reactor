package com.arc.reactor.tool.example

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 현재 시간 조회 도구 (예시)
 *
 * LLM이 현재 날짜/시간을 알아야 할 때 이 도구를 호출합니다.
 * 타임존을 지정하면 해당 지역의 시간을 반환합니다.
 */
@Component
class DateTimeTool : ToolCallback {

    override val name = "current_datetime"

    override val description = "Get the current date and time. Optionally specify a timezone (default: Asia/Seoul)"

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "timezone": {
                  "type": "string",
                  "description": "Timezone ID (e.g., Asia/Seoul, UTC, America/New_York, Europe/London)"
                }
              }
            }
        """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val timezoneId = arguments["timezone"] as? String ?: "Asia/Seoul"

        val zone = try {
            ZoneId.of(timezoneId)
        } catch (e: Exception) {
            return "Error: Invalid timezone '$timezoneId'. Examples: Asia/Seoul, UTC, America/New_York"
        }

        val now = ZonedDateTime.now(zone)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE)")
        return "${now.format(formatter)} [$timezoneId]"
    }
}
