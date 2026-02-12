package com.arc.reactor.response

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.response.impl.MaxLengthResponseFilter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [MaxLengthResponseFilter].
 *
 * Covers: truncation, boundary values, disabled state, truncation notice.
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
        fun `should truncate content exceeding max length`() = runTest {
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
        fun `should not truncate content within limit`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = 100)
            val content = "Short response"
            val result = filter.filter(content, context)

            assertEquals(content, result) { "Content within limit should be unchanged" }
        }

        @Test
        fun `should not truncate content at exact limit`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = 5)
            val result = filter.filter("Hello", context)

            assertEquals("Hello", result) { "Content at exact limit should not be truncated" }
        }

        @Test
        fun `should truncate content one char over limit`() = runTest {
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
        fun `should pass through when maxLength is 0`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = 0)
            val longContent = "A".repeat(100000)
            val result = filter.filter(longContent, context)

            assertEquals(longContent, result) { "maxLength=0 should not truncate" }
        }

        @Test
        fun `should pass through when maxLength is negative`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = -1)
            val content = "Any content"
            val result = filter.filter(content, context)

            assertEquals(content, result) { "Negative maxLength should not truncate" }
        }
    }

    @Nested
    inner class FilterOrder {

        @Test
        fun `should have low order value for early execution`() {
            val filter = MaxLengthResponseFilter()
            assertTrue(filter.order < 100) {
                "MaxLength filter should run before custom filters (order < 100), got: ${filter.order}"
            }
        }
    }

    @Nested
    inner class EmptyContent {

        @Test
        fun `should handle empty string`() = runTest {
            val filter = MaxLengthResponseFilter(maxLength = 10)
            val result = filter.filter("", context)

            assertEquals("", result) { "Empty content should remain empty" }
        }
    }
}
