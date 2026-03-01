package com.arc.reactor.agent.impl

import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.LocalToolFilter
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

internal class ToolPreparationPlanner(
    private val localTools: List<LocalTool>,
    private val toolCallbacks: List<ToolCallback>,
    private val mcpToolCallbacks: () -> List<ToolCallback>,
    private val toolSelector: ToolSelector?,
    private val maxToolsPerRequest: Int,
    private val fallbackToolTimeoutMs: Long,
    private val localToolFilters: List<LocalToolFilter> = emptyList()
) {

    fun prepareForPrompt(userPrompt: String): List<Any> {
        val localToolInstances = localToolFilters.fold(localTools.toList()) { acc, filter ->
            runCatching { filter.filter(acc) }
                .getOrElse { ex ->
                    logger.warn(ex) { "LocalToolFilter failed; keeping previously resolved tool list" }
                    acc
                }
        }
        val allCallbacks = deduplicateCallbacks(toolCallbacks + mcpToolCallbacks())
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

    private fun deduplicateCallbacks(callbacks: List<ToolCallback>): List<ToolCallback> {
        if (callbacks.isEmpty()) return emptyList()

        val uniqueByName = LinkedHashMap<String, ToolCallback>()
        for (callback in callbacks) {
            val existing = uniqueByName.putIfAbsent(callback.name, callback)
            if (existing != null && existing !== callback) {
                logger.warn { "Duplicate tool callback name '${callback.name}' detected; keeping first callback" }
            }
        }
        return uniqueByName.values.toList()
    }
}
