package com.arc.reactor.tool

/**
 * Tool Category Interface
 *
 * Classification system for dynamic tool loading.
 * Enables selecting only relevant tools based on user request,
 * reducing context window usage and improving tool selection accuracy.
 *
 * ## Why Categories?
 * - LLMs perform better with fewer, relevant tools
 * - Reduces token usage (tool descriptions consume context)
 * - Enables domain-specific tool groupings
 *
 * ## Custom Categories
 * ```kotlin
 * enum class MyProjectCategory(
 *     override val keywords: Set<String>
 * ) : ToolCategory {
 *     HR(setOf("employee", "hiring", "recruitment")),
 *     FINANCE(setOf("budget", "expense", "invoice"))
 * }
 * ```
 *
 * @see ToolSelector for category-based tool filtering
 * @see DefaultToolCategory for built-in categories
 */
interface ToolCategory {
    /** Category identifier */
    val name: String

    /** Keywords that trigger this category (lowercase) */
    val keywords: Set<String>

    /**
     * Check if the prompt contains keywords matching this category.
     *
     * @param prompt User's request text
     * @return true if any keyword matches
     */
    fun matches(prompt: String): Boolean {
        val lowerPrompt = prompt.lowercase()
        return keywords.any { it in lowerPrompt }
    }
}

/**
 * Default Tool Categories
 *
 * Built-in categories for common tool types.
 * Use these or define custom categories for your domain.
 */
enum class DefaultToolCategory(
    override val keywords: Set<String>
) : ToolCategory {
    /** Search and retrieval tools */
    SEARCH(setOf("검색", "search", "찾아", "find", "조회", "query")),

    /** Content creation tools */
    CREATE(setOf("생성", "create", "만들어", "작성", "write")),

    /** Analysis and reporting tools */
    ANALYZE(setOf("분석", "analyze", "요약", "summary", "리포트", "report")),

    /** Communication and notification tools */
    COMMUNICATE(setOf("전송", "send", "메일", "email", "알림", "notify")),

    /** Data management tools */
    DATA(setOf("데이터", "data", "저장", "save", "업데이트", "update"));

    companion object {
        /**
         * Extract all categories matching keywords in the prompt.
         *
         * @param prompt User's request text
         * @return Set of matching categories
         */
        fun matchCategories(prompt: String): Set<DefaultToolCategory> {
            return entries.filter { it.matches(prompt) }.toSet()
        }
    }
}
