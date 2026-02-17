package com.arc.reactor.tool.example

import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Current date/time tool (example)
 *
 * Called by the LLM when it needs to know the current date/time.
 * If a timezone is specified, returns the time for that region.
 *
 * This class is an example and is not annotated with @Component.
 * To use it, register it as a bean manually or add @Component.
 */
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
            e.throwIfCancellation()
            return "Error: Invalid timezone '$timezoneId'. Examples: Asia/Seoul, UTC, America/New_York"
        }

        val now = ZonedDateTime.now(zone)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE)")
        return "${now.format(formatter)} [$timezoneId]"
    }
}
