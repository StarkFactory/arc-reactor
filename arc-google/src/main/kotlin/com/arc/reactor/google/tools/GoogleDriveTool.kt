package com.arc.reactor.google.tools

import com.arc.reactor.google.GoogleCredentialProvider
import com.arc.reactor.tool.ToolCallback
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Google Drive에서 파일을 검색하는 ToolCallback.
 *
 * Google Drive API v3를 Service Account + Domain-Wide Delegation으로 사용한다.
 * 일치하는 파일 메타데이터를 JSON 리스트로 반환한다.
 * [call]에서 예외를 던지지 않고 에러를 문자열로 반환한다.
 *
 * @see GoogleCredentialProvider 자격 증명 생성
 */
class GoogleDriveTool(
    private val credentialProvider: GoogleCredentialProvider
) : ToolCallback {

    override val name: String = "google_drive_search"

    override val description: String =
        "Searches Google Drive files using a query string. Returns file metadata as JSON."

    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Drive search query (e.g. 'mimeType=application/vnd.google-apps.document')."
            },
            "max_results": {
              "type": "integer",
              "description": "Maximum number of results to return. Defaults to 10."
            }
          },
          "required": ["query"]
        }
    """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        return try {
            val query = arguments["query"] as? String
                ?: return "Error: query is required"
            val maxResults = (arguments["max_results"] as? Number)?.toInt() ?: DEFAULT_MAX_RESULTS
            val files = searchFiles(query, maxResults)
            objectMapper.writeValueAsString(files)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "GoogleDriveTool failed" }
            "Error: ${e.message}"
        }
    }

    /** Drive API로 파일을 검색하여 id, name, mimeType, modifiedTime, webViewLink를 반환한다. */
    private fun searchFiles(query: String, maxResults: Int): List<Map<String, Any?>> {
        val credentials = credentialProvider.getCredentials(listOf(DriveScopes.DRIVE_READONLY))
        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val service = Drive.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
            .setApplicationName(APP_NAME)
            .build()

        val result = service.files().list()
            .setQ(query)
            .setPageSize(maxResults)
            .setFields("files(id,name,mimeType,modifiedTime,webViewLink)")
            .execute()

        return result.files.orEmpty().map { file ->
            mapOf(
                "id" to file.id,
                "name" to file.name,
                "mimeType" to file.mimeType,
                "modifiedTime" to file.modifiedTime?.toString(),
                "webViewLink" to file.webViewLink
            )
        }
    }

    companion object {
        private const val APP_NAME = "arc-reactor-google"
        private const val DEFAULT_MAX_RESULTS = 10
        private val objectMapper = jacksonObjectMapper()
    }
}
