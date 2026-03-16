package com.arc.reactor.google

import com.arc.reactor.google.tools.GoogleSheetsTool
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * GoogleSheetsTool에 대한 테스트.
 *
 * Google 스프레드시트 도구의 데이터 읽기/쓰기 동작을 검증합니다.
 */
class GoogleSheetsToolTest {

    private val credentialProvider = mockk<GoogleCredentialProvider>()
    private val tool = GoogleSheetsTool(credentialProvider)

    @Test
    fun `tool name은(는) google_sheets_read이다`() {
        assertEquals(
            "google_sheets_read",
            tool.name,
            "Tool name must be 'google_sheets_read'"
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
    fun `call returns error when spreadsheet_id은(는) missing이다`() = runTest {
        val result = tool.call(mapOf("range" to "Sheet1!A1:D10"))

        assertTrue(
            result is String,
            "Result must be a String when spreadsheet_id is missing"
        )
        assertEquals(
            "Error: spreadsheet_id is required",
            result,
            "Result must indicate that spreadsheet_id is required"
        )
    }

    @Test
    fun `call returns error when range은(는) missing이다`() = runTest {
        val result = tool.call(mapOf("spreadsheet_id" to "sheet123"))

        assertTrue(
            result is String,
            "Result must be a String when range is missing"
        )
        assertEquals(
            "Error: range is required",
            result,
            "Result must indicate that range is required"
        )
    }

    @Test
    fun `returns error string when credential provider throws를 호출한다`() = runTest {
        every {
            credentialProvider.getCredentials(any())
        } throws RuntimeException("Service account key not found")

        val result = tool.call(
            mapOf("spreadsheet_id" to "sheet123", "range" to "Sheet1!A1:D10")
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
    fun `returns error string when authentication fails를 호출한다`() = runTest {
        every {
            credentialProvider.getCredentials(any())
        } throws IllegalArgumentException("arc.reactor.google.impersonate-user must be configured")

        val result = tool.call(
            mapOf("spreadsheet_id" to "sheet123", "range" to "Sheet1!A1:B2")
        )

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
            tool.call(mapOf("spreadsheet_id" to "sheet123", "range" to "Sheet1!A1:B2"))
            fail("CancellationException must be rethrown from GoogleSheetsTool.call")
        } catch (_: java.util.concurrent.CancellationException) {
            // 예상 결과
        }
    }
}
