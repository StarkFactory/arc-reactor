package com.arc.reactor.google.tools

import com.arc.reactor.google.GoogleCredentialProvider
import com.arc.reactor.tool.ToolCallback
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

private val logger = KotlinLogging.logger {}

/**
 * ToolCallback that lists calendar events for a given date.
 *
 * Uses Google Calendar API v3 with Service Account + Domain-Wide Delegation.
 * Returns errors as strings and never throws from [call].
 */
class GoogleCalendarTool(
    private val credentialProvider: GoogleCredentialProvider
) : ToolCallback {

    override val name: String = "google_calendar_list_events"

    override val description: String =
        "Lists Google Calendar events for a given date. Defaults to today if no date is provided."

    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "date": {
              "type": "string",
              "description": "Date in YYYY-MM-DD format. Defaults to today if omitted."
            }
          }
        }
    """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        return try {
            val date = (arguments["date"] as? String)?.let { LocalDate.parse(it) } ?: LocalDate.now()
            val events = fetchEvents(date)
            objectMapper.writeValueAsString(events)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "GoogleCalendarTool failed" }
            "Error: ${e.message}"
        }
    }

    private fun fetchEvents(date: LocalDate): List<Map<String, Any?>> {
        val credentials = credentialProvider.getCredentials(listOf(CalendarScopes.CALENDAR_READONLY))
        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val service = Calendar.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
            .setApplicationName(APP_NAME)
            .build()

        val zoneId = ZoneId.systemDefault()
        val startOfDay = date.atStartOfDay(zoneId).toInstant()
        val endOfDay = date.plusDays(1).atStartOfDay(zoneId).toInstant()

        val result = service.events().list("primary")
            .setTimeMin(DateTime(Date.from(startOfDay)))
            .setTimeMax(DateTime(Date.from(endOfDay)))
            .setSingleEvents(true)
            .setOrderBy("startTime")
            .execute()

        return result.items.orEmpty().map { event ->
            mapOf(
                "summary" to event.summary,
                "start" to (event.start?.dateTime?.toString() ?: event.start?.date?.toString()),
                "end" to (event.end?.dateTime?.toString() ?: event.end?.date?.toString()),
                "attendees" to event.attendees?.map { it.email }.orEmpty()
            )
        }
    }

    companion object {
        private const val APP_NAME = "arc-reactor-google"
        private val objectMapper = jacksonObjectMapper()
    }
}
