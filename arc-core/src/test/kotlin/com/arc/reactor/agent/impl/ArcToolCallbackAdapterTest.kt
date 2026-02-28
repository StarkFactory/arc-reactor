package com.arc.reactor.agent.impl

import com.arc.reactor.tool.ToolCallback
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

class ArcToolCallbackAdapterTest {

    @Test
    fun `returns callback output when tool execution succeeds`() {
        val callback = object : ToolCallback {
            override val name: String = "echo"
            override val description: String = "echo tool"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                return "echo:${arguments["message"]}"
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 500)

        val output = adapter.call("""{"message":"arc"}""")

        assertEquals("echo:arc", output)
    }

    @Test
    fun `throws timeout error when callback exceeds configured timeout`() {
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

        lateinit var error: RuntimeException
        val elapsedMs = measureTimeMillis {
            error = assertThrows(RuntimeException::class.java) {
                adapter.call("{}")
            }
        }

        assertTrue(error.message.orEmpty().contains("timed out after 30ms"), "Timeout error should mention the timeout duration")
        assertTrue(error.message.orEmpty().contains("slow_tool"), "Timeout error should mention the tool name")
        assertTrue(elapsedMs < 200, "Expected timeout to abort before full tool delay, elapsed=${elapsedMs}ms")
    }
}
