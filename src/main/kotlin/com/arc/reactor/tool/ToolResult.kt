package com.arc.reactor.tool

/**
 * Tool Execution Result Interface
 *
 * Standard interface for tool execution results.
 * Provides consistent success/failure handling across all tools.
 *
 * ## Usage
 * ```kotlin
 * @Tool(description = "Search companies")
 * fun searchCompany(name: String): ToolResult {
 *     return try {
 *         val results = companyService.search(name)
 *         SimpleToolResult.success("Found ${results.size} companies", results)
 *     } catch (e: Exception) {
 *         SimpleToolResult.failure("Search failed: ${e.message}")
 *     }
 * }
 * ```
 *
 * @see SimpleToolResult for basic implementation
 * @see CountableToolResult for results with item counts
 */
interface ToolResult {
    /** Whether the operation succeeded */
    val success: Boolean

    /** Human-readable message for successful operations */
    val message: String?

    /** Error description for failed operations */
    val errorMessage: String?

    /** Check if operation was successful (success flag and no error) */
    fun isSuccess(): Boolean = success && errorMessage == null

    /** Check if operation failed */
    fun isFailure(): Boolean = !isSuccess()

    /** Get appropriate message based on success/failure status */
    fun displayMessage(): String = if (isSuccess()) message.orEmpty() else errorMessage.orEmpty()
}

/**
 * Countable Tool Result Interface
 *
 * Extension for results that return collections of items.
 * Provides convenience methods for checking result counts.
 *
 * @property totalCount Number of items in the result
 */
interface CountableToolResult : ToolResult {
    /** Total number of items returned */
    val totalCount: Int

    /** Check if any items were returned */
    fun hasItems(): Boolean = totalCount > 0

    /** Check if no items were returned */
    fun isEmpty(): Boolean = totalCount == 0
}

/**
 * Simple Tool Result Implementation
 *
 * Basic data class implementing ToolResult.
 * Use factory methods for cleaner instantiation.
 *
 * ## Examples
 * ```kotlin
 * // Success with data
 * SimpleToolResult.success("Found 5 companies", companies)
 *
 * // Failure
 * SimpleToolResult.failure("Company not found")
 *
 * // Direct construction
 * SimpleToolResult(
 *     success = true,
 *     message = "Operation completed",
 *     data = resultData
 * )
 * ```
 *
 * @property success Whether operation succeeded
 * @property message Success message
 * @property errorMessage Error description
 * @property data Optional result payload
 */
data class SimpleToolResult(
    override val success: Boolean,
    override val message: String? = null,
    override val errorMessage: String? = null,
    val data: Any? = null
) : ToolResult {
    companion object {
        /**
         * Create a successful result.
         *
         * @param message Success message
         * @param data Optional result data
         */
        fun success(message: String, data: Any? = null) = SimpleToolResult(
            success = true,
            message = message,
            data = data
        )

        /**
         * Create a failure result.
         *
         * @param errorMessage Error description
         */
        fun failure(errorMessage: String) = SimpleToolResult(
            success = false,
            errorMessage = errorMessage
        )
    }
}
