package com.arc.reactor.google.tools

import com.arc.reactor.google.GoogleCredentialProvider
import com.arc.reactor.tool.ToolCallback
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * ToolCallback that searches Gmail messages.
 *
 * Uses Gmail API v1 with Service Account + Domain-Wide Delegation.
 * Returns a JSON list of matching message metadata. Returns errors as strings and never throws from [call].
 */
class GoogleGmailTool(
    private val credentialProvider: GoogleCredentialProvider
) : ToolCallback {

    override val name: String = "google_gmail_search"

    override val description: String =
        "Searches Gmail messages using a query string. Returns message metadata as JSON."

    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Gmail search query (e.g. 'is:unread category:primary')."
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
            val messages = searchMessages(query, maxResults)
            objectMapper.writeValueAsString(messages)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "GoogleGmailTool failed" }
            "Error: ${e.message}"
        }
    }

    private fun searchMessages(query: String, maxResults: Int): List<Map<String, Any?>> {
        val credentials = credentialProvider.getCredentials(listOf(GmailScopes.GMAIL_READONLY))
        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val service = Gmail.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
            .setApplicationName(APP_NAME)
            .build()

        val listResponse = service.users().messages().list(USER_ME)
            .setQ(query)
            .setMaxResults(maxResults.toLong())
            .execute()

        val messageIds = listResponse.messages.orEmpty()
        return messageIds.map { ref ->
            val msg = service.users().messages().get(USER_ME, ref.id)
                .setFormat("metadata")
                .setMetadataHeaders(listOf("From", "Subject", "Date"))
                .execute()
            val headers = msg.payload?.headers?.associateBy({ it.name }, { it.value }) ?: emptyMap()
            mapOf(
                "id" to msg.id,
                "from" to headers["From"],
                "subject" to headers["Subject"],
                "snippet" to msg.snippet,
                "date" to headers["Date"]
            )
        }
    }

    companion object {
        private const val APP_NAME = "arc-reactor-google"
        private const val USER_ME = "me"
        private const val DEFAULT_MAX_RESULTS = 10
        private val objectMapper = jacksonObjectMapper()
    }
}
