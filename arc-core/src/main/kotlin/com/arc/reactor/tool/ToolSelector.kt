package com.arc.reactor.tool

import com.arc.reactor.agent.config.ToolRoutingConfig

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
 * @see SemanticToolSelector for embedding-based semantic filtering
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
 * ## Config-driven construction
 * ```kotlin
 * // Build from tool-routing.yml — each route category becomes a ToolCategory
 * val selector = KeywordBasedToolSelector.fromRoutingConfig()
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

        val matchedCategories = toolCategoryMap.values
            .filter { it.matches(prompt) }
            .toSet()

        if (matchedCategories.isEmpty()) {
            return availableTools
        }

        return availableTools.filter { callback ->
            val category = toolCategoryMap[callback.name]
            category == null || category in matchedCategories
        }
    }

    companion object {
        /**
         * Build a KeywordBasedToolSelector from the unified tool-routing.yml config.
         *
         * Each route with non-empty preferredTools generates entries
         * in the toolCategoryMap: tool name -> ToolCategory (derived from route category + keywords).
         *
         * @param config The routing config (defaults to classpath-loaded)
         * @return A KeywordBasedToolSelector with category mappings from config
         */
        fun fromRoutingConfig(
            config: ToolRoutingConfig = ToolRoutingConfig.loadFromClasspath()
        ): KeywordBasedToolSelector {
            val categoryObjects = buildCategoryMap(config)
            val toolCategoryMap = mutableMapOf<String, ToolCategory>()

            for (route in config.routes) {
                if (route.preferredTools.isEmpty()) continue
                val category = categoryObjects[route.category] ?: continue
                for (toolName in route.preferredTools) {
                    toolCategoryMap.putIfAbsent(toolName, category)
                }
            }

            return KeywordBasedToolSelector(toolCategoryMap)
        }

        /**
         * Aggregate all keywords from routes sharing the same category
         * into a single ToolCategory per category name.
         */
        private fun buildCategoryMap(
            config: ToolRoutingConfig
        ): Map<String, ToolCategory> {
            val keywordsByCategory = mutableMapOf<String, MutableSet<String>>()
            for (route in config.routes) {
                val keywords = keywordsByCategory.getOrPut(route.category) {
                    mutableSetOf()
                }
                keywords.addAll(route.keywords)
                keywords.addAll(route.requiredKeywords)
            }

            return keywordsByCategory.map { (name, keywords) ->
                name to object : ToolCategory {
                    override val name = name
                    override val keywords = keywords.toSet()
                }
            }.toMap()
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
