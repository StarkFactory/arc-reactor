package com.arc.reactor.slack.tools.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ToolObservabilityAspectTest {

    private val meterRegistry = SimpleMeterRegistry()
    private val aspect = ToolObservabilityAspect(meterRegistry)

    @Test
    fun `records success metric for successful tool response`() {
        val joinPoint = mockJoinPoint(
            method = DummyTool::class.java.getDeclaredMethod("send_message", String::class.java, String::class.java),
            args = arrayOf("C12345678", "hello"),
            proceedResult = """{"ok":true}"""
        )

        val result = aspect.observeToolInvocation(joinPoint)

        assertEquals("""{"ok":true}""", result)
        assertEquals(
            1.0,
            meterRegistry.counter(
                "mcp_tool_invocations_total",
                "tool", "send_message",
                "outcome", "success",
                "error_code", "none"
            ).count()
        )
    }

    @Test
    fun `records failure metric for tool error response`() {
        val joinPoint = mockJoinPoint(
            method = DummyTool::class.java.getDeclaredMethod("send_message", String::class.java, String::class.java),
            args = arrayOf("C12345678", "hello"),
            proceedResult = """{"ok":false,"error":"rate_limited","errorDetails":{"code":"rate_limited"}}"""
        )

        aspect.observeToolInvocation(joinPoint)

        assertEquals(
            1.0,
            meterRegistry.counter(
                "mcp_tool_invocations_total",
                "tool", "send_message",
                "outcome", "failure",
                "error_code", "rate_limited"
            ).count()
        )
    }

    @Test
    fun `records exception metric when tool throws`() {
        val signature = mockk<MethodSignature>()
        every { signature.method } returns DummyTool::class.java.getDeclaredMethod(
            "send_message",
            String::class.java,
            String::class.java
        )

        val joinPoint = mockk<ProceedingJoinPoint>()
        every { joinPoint.signature } returns signature
        every { joinPoint.args } returns arrayOf("C12345678", "hello")
        every { joinPoint.proceed() } throws IllegalStateException("boom")

        assertThrows(IllegalStateException::class.java) {
            aspect.observeToolInvocation(joinPoint)
        }

        assertEquals(
            1.0,
            meterRegistry.counter(
                "mcp_tool_invocations_total",
                "tool", "send_message",
                "outcome", "exception",
                "error_code", "illegal_state_exception"
            ).count()
        )
    }

    private fun mockJoinPoint(
        method: java.lang.reflect.Method,
        args: Array<Any?>,
        proceedResult: String
    ): ProceedingJoinPoint {
        val signature = mockk<MethodSignature>()
        every { signature.method } returns method
        val joinPoint = mockk<ProceedingJoinPoint>()
        every { joinPoint.signature } returns signature
        every { joinPoint.args } returns args
        every { joinPoint.proceed() } returns proceedResult
        return joinPoint
    }

    private class DummyTool {
        @Suppress("unused")
        fun send_message(channelId: String, text: String): String = """{"ok":true}"""
    }
}
