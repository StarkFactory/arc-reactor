package com.arc.reactor.agent.impl

import com.arc.reactor.tool.ToolCallback
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

/**
 * ArcToolCallbackAdapter에 대한 테스트.
 *
 * ToolCallback을 Spring AI ToolCallback으로 변환하는
 * 어댑터의 동작을 검증합니다.
 */
class ArcToolCallbackAdapterTest {

    @Test
    fun `tool execution succeeds일 때 callback output를 반환한다`() {
        val callback = object : ToolCallback {
            override val name: String = "echo"
            override val description: String = "echo tool"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                return "echo:${arguments["message"]}"
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 500)

        val output = adapter.call("""{"message":"arc"}""")

        assertEquals("echo:arc", output, "Should return tool output as-is")
    }

    @Test
    fun `callback exceeds configured timeout일 때 error string를 반환한다`() {
        val callback = object : ToolCallback {
            override val name: String = "slow_tool"
            override val description: String = "slow tool"
            override val timeoutMs: Long = 30
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                delay(200)
                return "late"
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 500)

        lateinit var output: String
        val elapsedMs = measureTimeMillis {
            output = adapter.call("{}")
        }

        assertTrue(output.startsWith("Error:"), "Timeout should return error string, got: $output")
        assertTrue(output.contains("timed out after 30ms")) {
            "Timeout error should mention the timeout duration, got: $output"
        }
        assertTrue(output.contains("slow_tool"), "Timeout error should mention the tool name, got: $output")
        assertTrue(elapsedMs < 200, "Expected timeout to abort before full tool delay, elapsed=${elapsedMs}ms")
    }

    @Test
    fun `callback blocks thread with delay일 때 error string를 반환한다`() {
        val callback = object : ToolCallback {
            override val name: String = "blocking_tool"
            override val description: String = "blocking tool"
            override val timeoutMs: Long = 40
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                delay(300)
                return "late"
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 500)

        lateinit var output: String
        val elapsedMs = measureTimeMillis {
            output = adapter.call("{}")
        }

        assertTrue(output.startsWith("Error:"), "Timeout should return error string, got: $output")
        assertTrue(output.contains("timed out after 40ms")) {
            "Timeout should mention configured timeout, got: $output"
        }
        assertTrue(output.contains("blocking_tool"), "Timeout should mention tool name, got: $output")
        assertTrue(elapsedMs < 200) {
            "Callback should be cancelled by timeout, elapsed=${elapsedMs}ms"
        }
    }

    @Test
    fun `callback throws non-cancellation exception일 때 error string를 반환한다`() {
        val callback = object : ToolCallback {
            override val name: String = "failing_tool"
            override val description: String = "tool that throws"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                throw IllegalStateException("disk full")
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 500)

        val output = adapter.call("{}")

        assertTrue(output.startsWith("Error:"), "Exception should return error string, got: $output")
        assertTrue(output.contains("failing_tool"), "Error should mention tool name, got: $output")
        assertTrue(output.contains("disk full"), "Error should include original message, got: $output")
    }
}
