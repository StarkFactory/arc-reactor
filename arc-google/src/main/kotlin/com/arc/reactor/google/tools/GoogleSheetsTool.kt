package com.arc.reactor.google.tools

import com.arc.reactor.google.GoogleCredentialProvider
import com.arc.reactor.tool.ToolCallback
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Google Spreadsheet에서 셀 범위를 읽는 ToolCallback.
 *
 * Google Sheets API v4를 Service Account + Domain-Wide Delegation으로 사용한다.
 * 탭 구분(tab-separated) 텍스트를 반환한다.
 * [call]에서 예외를 던지지 않고 에러를 문자열로 반환한다.
 *
 * @see GoogleCredentialProvider 자격 증명 생성
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
            e.throwIfCancellation()
            logger.warn(e) { "GoogleSheetsTool failed" }
            "Error: ${e.message}"
        }
    }

    /** Sheets API로 지정 범위의 데이터를 조회하여 탭 구분 텍스트로 변환한다. */
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
