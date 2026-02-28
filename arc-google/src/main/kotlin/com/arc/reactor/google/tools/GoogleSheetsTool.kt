package com.arc.reactor.google.tools

import com.arc.reactor.google.GoogleCredentialProvider
import com.arc.reactor.tool.ToolCallback
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.auth.http.HttpCredentialsAdapter
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * ToolCallback that reads a range from a Google Spreadsheet.
 *
 * Uses Google Sheets API v4 with Service Account + Domain-Wide Delegation.
 * Returns tab-separated values. Returns errors as strings and never throws from [call].
 */
class GoogleSheetsTool(
    private val credentialProvider: GoogleCredentialProvider
) : ToolCallback {

    override val name: String = "google_sheets_read"

    override val description: String =
        "Reads a cell range from a Google Spreadsheet. Returns tab-separated values."

    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "spreadsheet_id": {
              "type": "string",
              "description": "The ID of the Google Spreadsheet (from its URL)."
            },
            "range": {
              "type": "string",
              "description": "A1 notation range, e.g. Sheet1!A1:D10."
            }
          },
          "required": ["spreadsheet_id", "range"]
        }
    """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        return try {
            val spreadsheetId = arguments["spreadsheet_id"] as? String
                ?: return "Error: spreadsheet_id is required"
            val range = arguments["range"] as? String
                ?: return "Error: range is required"
            fetchRange(spreadsheetId, range)
        } catch (e: Exception) {
            logger.warn(e) { "GoogleSheetsTool failed" }
            "Error: ${e.message}"
        }
    }

    private fun fetchRange(spreadsheetId: String, range: String): String {
        val credentials = credentialProvider.getCredentials(listOf(SheetsScopes.SPREADSHEETS_READONLY))
        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val service = Sheets.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
            .setApplicationName(APP_NAME)
            .build()

        val response = service.spreadsheets().values()
            .get(spreadsheetId, range)
            .execute()

        return response.getValues()?.joinToString("\n") { row ->
            row.joinToString("\t") { it?.toString().orEmpty() }
        } ?: ""
    }

    companion object {
        private const val APP_NAME = "arc-reactor-google"
    }
}
