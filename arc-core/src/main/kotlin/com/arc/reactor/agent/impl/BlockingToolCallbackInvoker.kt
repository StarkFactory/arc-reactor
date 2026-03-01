package com.arc.reactor.agent.impl

import com.arc.reactor.tool.ToolCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

/**
 * Executes a suspend [ToolCallback] from blocking integration points.
 */
internal class BlockingToolCallbackInvoker(
    private val fallbackTimeoutMs: Long
) {

    fun invokeWithTimeout(toolCallback: ToolCallback, arguments: Map<String, Any?>): String {
        val timeoutMs = resolveTimeoutMs(toolCallback)
        return runBlocking(Dispatchers.IO) {
            withTimeout(timeoutMs) {
                runInterruptible {
                    runBlocking { toolCallback.call(arguments)?.toString().orEmpty() }
                }
            }
        }
    }

    fun timeoutErrorMessage(toolCallback: ToolCallback): String {
        val timeoutMs = resolveTimeoutMs(toolCallback)
        return "Error: Tool '${toolCallback.name}' timed out after ${timeoutMs}ms"
    }

    private fun resolveTimeoutMs(toolCallback: ToolCallback): Long {
        return (toolCallback.timeoutMs ?: fallbackTimeoutMs).coerceAtLeast(1)
    }
}
