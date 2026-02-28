package com.arc.reactor.agent.impl

import com.arc.reactor.tool.ToolCallback
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Tag("matrix")
class ArcToolCallbackAdapterFuzzTest {

    @Test
    fun `adapter should pass empty args for malformed inputs across 400 cases`() {
        val calls = AtomicInteger(0)
        val callback = object : ToolCallback {
            override val name = "malformed-check"
            override val description = "checks malformed parsing"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                calls.incrementAndGet()
                return "args=${arguments.size}"
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 200)
        val random = Random(777)

        repeat(400) { i ->
            val malformed = when (i % 4) {
                0 -> "broken-json-$i"
                1 -> """{"k$i": }"""
                2 -> """{"k":"v""" // unclosed
                else -> buildString {
                    append("{\"raw\":")
                    append((1..12).joinToString("") { (('a'.code + random.nextInt(26)).toChar()).toString() })
                }
            }
            val output = adapter.call(malformed)
            assertEquals("args=0", output, "input='$malformed'")
        }

        assertEquals(400, calls.get())
    }

    @Test
    fun `adapter should forward parsed values for 200 valid payloads`() {
        val callback = object : ToolCallback {
            override val name = "valid-check"
            override val description = "checks valid parsing"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                return "id=${arguments["id"]},flag=${arguments["flag"]}"
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 200)

        repeat(200) { i ->
            val json = """{"id":$i,"flag":${i % 2 == 0}}"""
            val output = adapter.call(json)
            assertEquals("id=$i,flag=${i % 2 == 0}", output)
        }
    }

    @Test
    fun `adapter should convert null callback result to empty string`() {
        val callback = object : ToolCallback {
            override val name = "null-result"
            override val description = "returns null"
            override suspend fun call(arguments: Map<String, Any?>): Any? = null
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 200)

        assertEquals("", adapter.call("""{"a":1}"""))
    }

    @Test
    fun `adapter should propagate cancellation exception`() {
        val callback = object : ToolCallback {
            override val name = "cancel-tool"
            override val description = "throws cancellation"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                throw CancellationException("cancelled by test")
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 200)

        val ex = assertThrows(CancellationException::class.java) {
            adapter.call("{}")
        }
        assertTrue(ex.message.orEmpty().contains("cancelled"), "CancellationException message should indicate cancellation")
    }
}
