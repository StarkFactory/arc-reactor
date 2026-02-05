package com.arc.reactor.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Tool 실행 유틸리티
 *
 * 동기/비동기 Tool 실행을 위한 헬퍼 함수들.
 */
object ToolExecutor {

    /**
     * Tool 함수를 비동기로 실행
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
     * Tool 함수를 동기로 실행 (blocking)
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
     * 타임아웃과 함께 실행
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
