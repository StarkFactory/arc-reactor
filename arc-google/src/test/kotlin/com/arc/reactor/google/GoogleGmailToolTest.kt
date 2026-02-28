package com.arc.reactor.google

import com.arc.reactor.google.tools.GoogleGmailTool
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoogleGmailToolTest {

    private val credentialProvider = mockk<GoogleCredentialProvider>()
    private val tool = GoogleGmailTool(credentialProvider)

    @Test
    fun `tool name is google_gmail_search`() {
        assertEquals(
            "google_gmail_search",
            tool.name,
            "Tool name must be 'google_gmail_search'"
        )
    }

    @Test
    fun `tool description is non-blank`() {
        assertTrue(
            tool.description.isNotBlank(),
            "Tool description must not be blank"
        )
    }

    @Test
    fun `call returns error when query is missing`() = runTest {
        val result = tool.call(mapOf("max_results" to 5))

        assertTrue(
            result is String,
            "Result must be a String when query is missing"
        )
        assertEquals(
            "Error: query is required",
            result,
            "Result must indicate that query is required"
        )
    }

    @Test
    fun `call returns error string when credential provider throws`() = runTest {
        every {
            credentialProvider.getCredentials(any())
        } throws RuntimeException("Gmail API not enabled for this project")

        val result = tool.call(
            mapOf("query" to "is:unread category:primary", "max_results" to 5)
        )

        assertTrue(
            result is String,
            "Result must be a String when an error occurs"
        )
        assertTrue(
            (result as String).startsWith("Error:"),
            "Result must start with 'Error:' but was: $result"
        )
    }

    @Test
    fun `call uses default max_results when not provided`() = runTest {
        every {
            credentialProvider.getCredentials(any())
        } throws RuntimeException("simulated API error")

        val result = tool.call(mapOf("query" to "is:unread"))

        assertTrue(
            (result as String).startsWith("Error:"),
            "Result must start with 'Error:' even when max_results is omitted"
        )
    }

    @Test
    fun `call returns error string when authentication fails with IllegalArgumentException`() = runTest {
        every {
            credentialProvider.getCredentials(any())
        } throws IllegalArgumentException("arc.reactor.google.impersonate-user must be configured")

        val result = tool.call(mapOf("query" to "subject:invoice"))

        assertTrue(
            (result as String).startsWith("Error:"),
            "Result must start with 'Error:' when IllegalArgumentException is thrown"
        )
    }
}
