package com.arc.reactor.response

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.response.impl.MaxLengthResponseFilter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [MaxLengthResponseFilter]에 대한 테스트.
 *
 * 대상: 잘라내기, 경계값, 비활성 상태, 잘라내기 알림.
 */
class MaxLengthResponseFilterTest {

    private val context = ResponseFilterContext(
        command = AgentCommand(systemPrompt = "Test", userPrompt = "Hello"),
        toolsUsed = emptyList(),
        durationMs = 100
    )

    @Nested
    inner class Truncation {

        @Test
        fun `truncate content exceeding max length해야 한다`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = 10)
            val result = filter.filter("This is a long response that should be truncated", context)

            assertTrue(result.startsWith("This is a ")) {
                "Should keep first 10 characters, got: ${result.take(20)}"
            }
            assertTrue(result.contains("[Response truncated]")) {
                "Should include truncation notice"
            }
        }

        @Test
        fun `not truncate content within limit해야 한다`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = 100)
            val content = "Short response"
            val result = filter.filter(content, context)

            assertEquals(content, result) { "Content within limit should be unchanged" }
        }

        @Test
        fun `not truncate content at exact limit해야 한다`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = 5)
            val result = filter.filter("Hello", context)

            assertEquals("Hello", result) { "Content at exact limit should not be truncated" }
        }

        @Test
        fun `truncate content one char over limit해야 한다`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = 5)
            val result = filter.filter("Hello!", context)

            assertTrue(result.startsWith("Hello")) {
                "Should keep first 5 chars"
            }
            assertTrue(result.contains("[Response truncated]")) {
                "One char over should trigger truncation"
            }
        }
    }

    @Nested
    inner class Disabled {

        @Test
        fun `maxLength is 0일 때 pass through해야 한다`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = 0)
            val longContent = "A".repeat(100000)
            val result = filter.filter(longContent, context)

            assertEquals(longContent, result) { "maxLength=0 should not truncate" }
        }

        @Test
        fun `maxLength is negative일 때 pass through해야 한다`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = -1)
            val content = "Any content"
            val result = filter.filter(content, context)

            assertEquals(content, result) { "Negative maxLength should not truncate" }
        }
    }

    @Nested
    inner class FilterOrder {

        @Test
        fun `early execution에 대해 have low order value해야 한다`() {
            val filter = MaxLengthResponseFilter()
            assertTrue(filter.order < 100) {
                "MaxLength filter should run before custom filters (order < 100), got: ${filter.order}"
            }
        }
    }

    @Nested
    inner class EmptyContent {

        @Test
        fun `handle empty string해야 한다`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = 10)
            val result = filter.filter("", context)

            assertEquals("", result) { "Empty content should remain empty" }
        }
    }
}
