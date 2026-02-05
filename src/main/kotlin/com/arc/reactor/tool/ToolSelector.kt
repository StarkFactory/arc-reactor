package com.arc.reactor.tool

/**
 * Tool Selection Strategy Interface
 *
 * Filters and selects appropriate tools based on user request.
 * Optimizes context window usage and improves tool selection accuracy.
 *
 * ## Why Tool Selection Matters
 * - LLMs have limited context windows
 * - Sending all tools increases token usage and costs
 * - Too many tools can confuse the LLM's tool selection
 * - Relevant tools improve response quality
 *
 * ## Example Usage
 * ```kotlin
 * val selector = KeywordBasedToolSelector(toolCategoryMap)
 * val relevantTools = selector.select(
 *     prompt = "Search for company information",
 *     availableTools = allTools
 * )
 * // Only SEARCH category tools returned
 * ```
 *
 * @see KeywordBasedToolSelector for keyword-based filtering
 * @see AllToolSelector for no filtering (pass-through)
 * @see ToolCategory for defining tool categories
 */
interface ToolSelector {
    /**
     * Select relevant tools based on the user prompt.
     *
     * @param prompt User's request text
     * @param availableTools All registered tools
     * @return Filtered list of tools relevant to the prompt
     */
    fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback>
}

/**
 * Keyword-Based Tool Selector
 *
 * Matches tool categories against keywords in the user prompt.
 * Falls back to returning all tools if no categories match.
 *
 * ## Matching Logic
 * 1. Extract categories that match keywords in the prompt
 * 2. If matches found: return tools in matched categories + uncategorized tools
 * 3. If no matches: return all tools (safe fallback)
 *
 * ## Example
 * ```kotlin
 * val selector = KeywordBasedToolSelector(mapOf(
 *     "search_company" to ToolCategory.SEARCH,
 *     "send_email" to ToolCategory.EMAIL
 * ))
 *
 * // Prompt contains "search" → returns SEARCH tools
 * selector.select("Search for Samsung", tools)
 *
 * // Prompt contains no keywords → returns all tools
 * selector.select("Hello", tools)
 * ```
 *
 * @param toolCategoryMap Mapping of tool names to their categories
 */
class KeywordBasedToolSelector(
    private val toolCategoryMap: Map<String, ToolCategory> = emptyMap()
) : ToolSelector {

    override fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        if (toolCategoryMap.isEmpty()) {
            return availableTools
        }

        // Extract categories matching keywords in the prompt
        val matchedCategories = toolCategoryMap.values
            .filter { it.matches(prompt) }
            .toSet()

        // No matches → return all tools (safe fallback)
        if (matchedCategories.isEmpty()) {
            return availableTools
        }

        // Return tools in matched categories + uncategorized tools
        return availableTools.filter { callback ->
            val category = toolCategoryMap[callback.name]
            category == null || category in matchedCategories
        }
    }
}

/**
 * Pass-Through Tool Selector
 *
 * Returns all tools without filtering.
 * Use when tool selection is not needed or handled elsewhere.
 *
 * ## Use Cases
 * - Development/testing with all tools available
 * - Small tool sets where filtering overhead isn't worth it
 * - Custom selection logic implemented in agent
 */
class AllToolSelector : ToolSelector {
    override fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        return availableTools
    }
}
