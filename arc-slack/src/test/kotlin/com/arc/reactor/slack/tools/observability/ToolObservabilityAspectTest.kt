package com.arc.reactor.slack.tools.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [ToolObservabilityAspect]의 도구 호출 메트릭 기록 테스트.
 *
 * 성공/실패/예외 시나리오에서 Micrometer 카운터가 올바른 태그와 함께
 * 기록되는지 검증한다.
 */
class ToolObservabilityAspectTest {

    private val meterRegistry = SimpleMeterRegistry()
    private val aspect = ToolObservabilityAspect(meterRegistry)

    @Test
    fun `success metric for successful tool response를 기록한다`() {
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
    fun `failure metric for tool error response를 기록한다`() {
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

    // 도구 호출 중 예외 발생 시 예외 메트릭을 기록하고 예외를 재전파
    @Test
    fun `exception metric when tool throws를 기록한다`() {
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
