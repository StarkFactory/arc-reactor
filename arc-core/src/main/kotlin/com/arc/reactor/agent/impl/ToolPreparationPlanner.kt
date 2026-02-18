package com.arc.reactor.agent.impl

import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector

internal class ToolPreparationPlanner(
    private val localTools: List<LocalTool>,
    private val toolCallbacks: List<ToolCallback>,
    private val mcpToolCallbacks: () -> List<ToolCallback>,
    private val toolSelector: ToolSelector?,
    private val maxToolsPerRequest: Int,
    private val fallbackToolTimeoutMs: Long
) {

    fun prepareForPrompt(userPrompt: String): List<Any> {
        val localToolInstances = localTools.toList()
        val allCallbacks = toolCallbacks + mcpToolCallbacks()
        val selectedCallbacks = if (toolSelector != null && allCallbacks.isNotEmpty()) {
            toolSelector.select(userPrompt, allCallbacks)
        } else {
            allCallbacks
        }
        val wrappedCallbacks = selectedCallbacks.map {
            ArcToolCallbackAdapter(
                arcCallback = it,
                fallbackToolTimeoutMs = fallbackToolTimeoutMs
            )
        }
        return (localToolInstances + wrappedCallbacks).take(maxToolsPerRequest)
    }
}
