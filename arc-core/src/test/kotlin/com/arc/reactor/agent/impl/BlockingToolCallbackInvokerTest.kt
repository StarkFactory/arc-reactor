package com.arc.reactor.agent.impl

import com.arc.reactor.tool.ToolCallback
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BlockingToolCallbackInvokerTest {

    private val invoker = BlockingToolCallbackInvoker(fallbackTimeoutMs = 5000)

    private fun mockTool(
        name: String = "test-tool",
        timeout: Long? = null,
        result: suspend () -> Any? = { "ok" }
    ): ToolCallback {
        val tool = mockk<ToolCallback>()
        every { tool.name } returns name
        every { tool.timeoutMs } returns timeout
        coEvery { tool.call(any()) } coAnswers { result() }
        return tool
    }

    @Nested
    inner class SuccessfulInvocation {

        @Test
        fun `returns string result from tool`() {
            val tool = mockTool(result = { "hello" })
            invoker.invokeWithTimeout(tool, emptyMap()) shouldBe "hello"
        }

        @Test
        fun `converts non-string result to string`() {
            val tool = mockTool(result = { 42 })
            invoker.invokeWithTimeout(tool, emptyMap()) shouldBe "42"
        }

        @Test
        fun `null result returns empty string`() {
            val tool = mockTool(result = { null })
            invoker.invokeWithTimeout(tool, emptyMap()) shouldBe ""
        }
    }

    @Nested
    inner class TimeoutBehavior {

        @Test
        fun `uses tool-specific timeout when provided`() {
            val tool = mockTool(timeout = 100, result = {
                delay(500)
                "late"
            })
            assertThrows<Exception> {
                invoker.invokeWithTimeout(tool, emptyMap())
            }
        }

        @Test
        fun `timeout error message includes tool name and duration`() {
            val tool = mockTool(name = "slow-tool", timeout = 3000)
            val msg = invoker.timeoutErrorMessage(tool)
            msg shouldContain "slow-tool"
            msg shouldContain "3000ms"
        }

        @Test
        fun `timeout coerced to at least 1ms`() {
            val tool = mockTool(name = "zero-timeout", timeout = 0)
            val msg = invoker.timeoutErrorMessage(tool)
            msg shouldContain "1ms"
        }
    }
}
