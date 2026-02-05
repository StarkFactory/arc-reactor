package com.arc.reactor.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Tool Execution Utility
 *
 * Provides helper functions for safe tool execution with logging,
 * error handling, and timeout support.
 *
 * ## Features
 * - Async execution on IO dispatcher
 * - Sync execution for blocking operations
 * - Timeout-based execution with cancellation
 * - Automatic logging with duration tracking
 * - Error capture via Kotlin Result type
 *
 * ## Example Usage
 * ```kotlin
 * // Async execution
 * val result = ToolExecutor.executeAsync("search_company") {
 *     companyService.search("Samsung")
 * }
 *
 * result.fold(
 *     onSuccess = { companies -> processResults(companies) },
 *     onFailure = { error -> handleError(error) }
 * )
 *
 * // With timeout
 * val timedResult = ToolExecutor.executeWithTimeout("slow_api", 5000L) {
 *     externalApi.call()
 * }
 * ```
 *
 * @see ToolCallback for the tool interface
 */
object ToolExecutor {

    /**
     * Execute a tool function asynchronously on IO dispatcher.
     *
     * Runs the block on [Dispatchers.IO] suitable for blocking I/O operations.
     * Logs execution start, completion time, and any failures.
     *
     * @param T Return type of the tool function
     * @param toolName Tool identifier for logging
     * @param block Suspending function to execute
     * @return [Result] containing the result or exception
     */
    suspend fun <T> executeAsync(
        toolName: String,
        block: suspend () -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            logger.debug { "Executing tool: $toolName" }
            val startTime = System.currentTimeMillis()

            val result = block()

            val duration = System.currentTimeMillis() - startTime
            logger.debug { "Tool $toolName completed in ${duration}ms" }

            result
        }.onFailure { e ->
            logger.error(e) { "Tool $toolName failed: ${e.message}" }
        }
    }

    /**
     * Execute a tool function synchronously (blocking).
     *
     * Use for simple, fast operations that don't need async handling.
     * Logs execution timing and any failures.
     *
     * @param T Return type of the tool function
     * @param toolName Tool identifier for logging
     * @param block Function to execute
     * @return [Result] containing the result or exception
     */
    fun <T> executeSync(
        toolName: String,
        block: () -> T
    ): Result<T> = runCatching {
        logger.debug { "Executing tool (sync): $toolName" }
        val startTime = System.currentTimeMillis()

        val result = block()

        val duration = System.currentTimeMillis() - startTime
        logger.debug { "Tool $toolName completed in ${duration}ms" }

        result
    }.onFailure { e ->
        logger.error(e) { "Tool $toolName failed: ${e.message}" }
    }

    /**
     * Execute a tool function with timeout.
     *
     * Cancels the operation if it exceeds the specified timeout.
     * Useful for external API calls that might hang.
     *
     * @param T Return type of the tool function
     * @param toolName Tool identifier for logging
     * @param timeoutMs Maximum execution time in milliseconds
     * @param block Suspending function to execute
     * @return [Result] containing the result, timeout exception, or other exception
     */
    suspend fun <T> executeWithTimeout(
        toolName: String,
        timeoutMs: Long,
        block: suspend () -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                block()
            }
        }.onFailure { e ->
            logger.error(e) { "Tool $toolName timed out or failed" }
        }
    }
}
