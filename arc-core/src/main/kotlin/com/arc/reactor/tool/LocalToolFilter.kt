package com.arc.reactor.tool

/**
 * Extension point to filter LocalTool beans before they are offered to the LLM.
 *
 * Implementations can enforce module-specific visibility policies
 * (e.g., scope-based exposure).
 */
fun interface LocalToolFilter {
    fun filter(tools: List<LocalTool>): List<LocalTool>
}
