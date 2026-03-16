package com.arc.reactor.google

import com.arc.reactor.google.tools.GoogleCalendarTool
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * GoogleCalendarTool에 대한 테스트.
 *
 * Google 캘린더 도구의 이벤트 조회 동작을 검증합니다.
 */
class GoogleCalendarToolTest {

    private val credentialProvider = mockk<GoogleCredentialProvider>()
    private val tool = GoogleCalendarTool(credentialProvider)

    @Test
    fun `tool name은(는) google_calendar_list_events이다`() {
        assertEquals(
            "google_calendar_list_events",
            tool.name,
            "Tool name must be 'google_calendar_list_events'"
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
    fun `tool inputSchema은(는) non-blank이다`() {
        assertTrue(
            tool.inputSchema.isNotBlank(),
            "Tool inputSchema must not be blank"
        )
    }

    @Test
    fun `returns error string when credential provider throws를 호출한다`() = runTest {
        every {
            credentialProvider.getCredentials(any())
        } throws RuntimeException("Service account key not found")

        val result = tool.call(mapOf("date" to "2025-01-15"))

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
    fun `returns error string when credential provider throws with no arguments를 호출한다`() = runTest {
        every {
            credentialProvider.getCredentials(any())
        } throws RuntimeException("Authentication failed")

        val result = tool.call(emptyMap())

        assertTrue(
            result is String,
            "Result must be a String when an error occurs with empty arguments"
        )
        assertTrue(
            (result as String).startsWith("Error:"),
            "Result must start with 'Error:' when authentication fails"
        )
    }

    @Test
    fun `returns error string when credential provider throws IllegalArgumentException를 호출한다`() = runTest {
        every {
            credentialProvider.getCredentials(any())
        } throws IllegalArgumentException("arc.reactor.google.service-account-key-path must be configured")

        val result = tool.call(mapOf("date" to "2025-01-15"))

        assertTrue(
            (result as String).startsWith("Error:"),
            "Result must start with 'Error:' when IllegalArgumentException is thrown"
        )
        assertTrue(
            result.contains("service-account-key-path"),
            "Error message should contain the configuration key hint"
        )
    }

    @Test
    fun `rethrows CancellationException for structured concurrency를 호출한다`() = runTest {
        every {
            credentialProvider.getCredentials(any())
        } throws java.util.concurrent.CancellationException("cancelled")

        try {
            tool.call(mapOf("date" to "2025-01-15"))
            fail("CancellationException must be rethrown from GoogleCalendarTool.call")
        } catch (_: java.util.concurrent.CancellationException) {
            // 예상 결과
        }
    }
}
