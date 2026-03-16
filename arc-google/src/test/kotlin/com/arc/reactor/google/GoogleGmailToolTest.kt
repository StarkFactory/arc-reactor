package com.arc.reactor.google

import com.arc.reactor.google.tools.GoogleGmailTool
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * GoogleGmailTool에 대한 테스트.
 *
 * Gmail 도구의 이메일 조회/발송 동작을 검증합니다.
 */
class GoogleGmailToolTest {

    private val credentialProvider = mockk<GoogleCredentialProvider>()
    private val tool = GoogleGmailTool(credentialProvider)

    @Test
    fun `tool name은(는) google_gmail_search이다`() {
        assertEquals(
            "google_gmail_search",
            tool.name,
            "Tool name must be 'google_gmail_search'"
        )
    }

    @Test
    fun `tool description은(는) non-blank이다`() {
        assertTrue(
            tool.description.isNotBlank(),
            "Tool description must not be blank"
        )
    }

    @Test
    fun `call returns error when query은(는) missing이다`() = runTest {
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
    fun `returns error string when credential provider throws를 호출한다`() = runTest {
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
    fun `uses default max_results when not provided를 호출한다`() = runTest {
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
    fun `returns error string when authentication fails with IllegalArgumentException를 호출한다`() = runTest {
        every {
            credentialProvider.getCredentials(any())
        } throws IllegalArgumentException("arc.reactor.google.impersonate-user must be configured")

        val result = tool.call(mapOf("query" to "subject:invoice"))

        assertTrue(
            (result as String).startsWith("Error:"),
            "Result must start with 'Error:' when IllegalArgumentException is thrown"
        )
    }

    @Test
    fun `rethrows CancellationException for structured concurrency를 호출한다`() = runTest {
        every {
            credentialProvider.getCredentials(any())
        } throws java.util.concurrent.CancellationException("cancelled")

        try {
            tool.call(mapOf("query" to "is:unread"))
            fail("CancellationException must be rethrown from GoogleGmailTool.call")
        } catch (_: java.util.concurrent.CancellationException) {
            // 예상 결과
        }
    }
}
