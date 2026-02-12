package com.arc.reactor.tool

/**
 * Local Tool Marker Interface
 *
 * Classes implementing this interface and annotated with @Component
 * are automatically registered with the Agent.
 *
 * ## Features
 * - Auto-discovery via Spring component scanning
 * - Category-based filtering for context window optimization
 * - Integration with Spring AI's @Tool annotation
 *
 * ## Example Usage
 * ```kotlin
 * @Component
 * class CompanySearchTool : LocalTool {
 *     override val category = ToolCategory.SEARCH
 *
 *     @Tool(description = "Search company information by name")
 *     fun searchCompany(@ToolParam("Company name") name: String): CompanyInfo {
 *         return companyService.search(name)
 *     }
 * }
 * ```
 *
 * ## Category Usage
 * ```kotlin
 * // Always loaded (essential tool)
 * class CoreTool : LocalTool {
 *     override val category = null  // or omit entirely
 * }
 *
 * // Conditionally loaded based on user request
 * class SearchTool : LocalTool {
 *     override val category = ToolCategory.SEARCH
 * }
 * ```
 *
 * @see ToolCategory for available tool categories
 * @see ToolSelector for category-based tool filtering
 * @see ToolResult for tool execution results
 */
interface LocalTool {
    /**
     * The category this tool belongs to.
     *
     * Used by [ToolSelector] to filter tools based on user request.
     * - `null`: Always loaded (essential/core tools)
     * - Non-null: Loaded only when category matches user intent
     *
     * @return The tool category, or null if always required
     */
    val category: ToolCategory?
        get() = null
}
