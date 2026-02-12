package com.arc.reactor.response

import com.arc.reactor.agent.model.AgentCommand
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [ResponseFilterChain].
 *
 * Covers: filter ordering, error handling (fail-open), empty chain, CancellationException propagation.
 */
class ResponseFilterChainTest {

    private val defaultContext = ResponseFilterContext(
        command = AgentCommand(systemPrompt = "Test", userPrompt = "Hello"),
        toolsUsed = emptyList(),
        durationMs = 100
    )

    @Nested
    inner class BasicFunctionality {

        @Test
        fun `should return content unchanged when no filters`() = runTest {
            val chain = ResponseFilterChain(emptyList())
            val result = chain.apply("Hello World", defaultContext)
            assertEquals("Hello World", result) { "Empty chain should return content as-is" }
        }

        @Test
        fun `should apply single filter`() = runTest {
            val filter = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext) =
                    content.uppercase()
            }
            val chain = ResponseFilterChain(listOf(filter))
            val result = chain.apply("hello", defaultContext)
            assertEquals("HELLO", result) { "Single filter should transform content" }
        }

        @Test
        fun `should apply multiple filters in order`() = runTest {
            val appendA = object : ResponseFilter {
                override val order = 10
                override suspend fun filter(content: String, context: ResponseFilterContext) =
                    "${content}A"
            }
            val appendB = object : ResponseFilter {
                override val order = 20
                override suspend fun filter(content: String, context: ResponseFilterContext) =
                    "${content}B"
            }
            val chain = ResponseFilterChain(listOf(appendB, appendA)) // intentionally reversed
            val result = chain.apply("X", defaultContext)
            assertEquals("XAB", result) { "Filters should execute in order (10 before 20)" }
        }

        @Test
        fun `should report correct size`() {
            val filter1 = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext) = content
            }
            val filter2 = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext) = content
            }
            val chain = ResponseFilterChain(listOf(filter1, filter2))
            assertEquals(2, chain.size) { "Chain should report correct filter count" }
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `should skip failing filter and continue with previous content`() = runTest {
            val callOrder = AtomicInteger(0)
            val failingFilter = object : ResponseFilter {
                override val order = 10
                override suspend fun filter(content: String, context: ResponseFilterContext): String {
                    callOrder.incrementAndGet()
                    throw RuntimeException("Filter broke")
                }
            }
            val appendFilter = object : ResponseFilter {
                override val order = 20
                override suspend fun filter(content: String, context: ResponseFilterContext): String {
                    callOrder.incrementAndGet()
                    return "${content}+suffix"
                }
            }
            val chain = ResponseFilterChain(listOf(failingFilter, appendFilter))
            val result = chain.apply("original", defaultContext)

            assertEquals("original+suffix", result) {
                "Failing filter should be skipped, next filter gets original content"
            }
            assertEquals(2, callOrder.get()) { "Both filters should have been called" }
        }

        @Test
        fun `should propagate CancellationException`() = runTest {
            val cancelFilter = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext): String {
                    throw kotlin.coroutines.cancellation.CancellationException("cancelled")
                }
            }
            val chain = ResponseFilterChain(listOf(cancelFilter))

            assertThrows(kotlin.coroutines.cancellation.CancellationException::class.java) {
                kotlinx.coroutines.runBlocking { chain.apply("test", defaultContext) }
            }
        }
    }

    @Nested
    inner class ContextPassing {

        @Test
        fun `should pass context to filters`() = runTest {
            var receivedContext: ResponseFilterContext? = null
            val capturingFilter = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext): String {
                    receivedContext = context
                    return content
                }
            }

            val context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "Custom", userPrompt = "Test"),
                toolsUsed = listOf("calculator", "weather"),
                durationMs = 500
            )

            ResponseFilterChain(listOf(capturingFilter)).apply("content", context)

            assertNotNull(receivedContext) { "Filter should receive context" }
            assertEquals(listOf("calculator", "weather"), receivedContext?.toolsUsed) {
                "Context should contain correct tools"
            }
            assertEquals(500L, receivedContext?.durationMs) { "Context should contain correct duration" }
        }
    }
}
