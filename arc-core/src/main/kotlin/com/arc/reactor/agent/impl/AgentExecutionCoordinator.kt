package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.cache.CacheKeyBuilder
import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.support.runSuspendCatchingNonCancellation
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import mu.KotlinLogging
import org.springframework.ai.chat.messages.Message

private val logger = KotlinLogging.logger {}

internal class AgentExecutionCoordinator(
    private val responseCache: ResponseCache?,
    private val cacheableTemperature: Double,
    private val defaultTemperature: Double,
    private val fallbackStrategy: FallbackStrategy?,
    private val agentMetrics: AgentMetrics,
    private val toolCallbacks: List<ToolCallback>,
    private val mcpToolCallbacks: () -> List<ToolCallback>,
    private val conversationManager: ConversationManager,
    private val selectAndPrepareTools: (String) -> List<Any>,
    private val retrieveRagContext: suspend (AgentCommand) -> String?,
    private val executeWithTools: suspend (
        AgentCommand,
        List<Any>,
        List<Message>,
        HookContext,
        MutableList<String>,
        String?
    ) -> AgentResult,
    private val finalizeExecution: suspend (
        AgentResult,
        AgentCommand,
        HookContext,
        List<String>,
        Long
    ) -> AgentResult,
    private val checkGuardAndHooks: suspend (AgentCommand, HookContext, Long) -> AgentResult?,
    private val resolveIntent: suspend (AgentCommand) -> AgentCommand,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    suspend fun execute(
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        startTime: Long
    ): AgentResult {
        checkGuardAndHooks(command, hookContext, startTime)?.let { return it }
        val effectiveCommand = resolveIntent(command)

        val cacheLookup = resolveCache(effectiveCommand, startTime)
        cacheLookup.cachedResult?.let { return it }
        val resolvedCacheKey = cacheLookup.cacheKey

        val conversationHistory = conversationManager.loadHistory(effectiveCommand)
        val ragContext = retrieveRagContext(effectiveCommand)
        val selectedTools = if (effectiveCommand.mode == AgentMode.STANDARD) {
            emptyList()
        } else {
            selectAndPrepareTools(effectiveCommand.userPrompt)
        }
        logger.debug { "Selected ${selectedTools.size} tools for execution (mode=${effectiveCommand.mode})" }

        var result = executeWithTools(
            effectiveCommand,
            selectedTools,
            conversationHistory,
            hookContext,
            toolsUsed,
            ragContext
        )

        if (!result.success && fallbackStrategy != null) {
            result = attemptFallback(effectiveCommand, result)
        }

        val finalResult = finalizeExecution(
            result,
            effectiveCommand,
            hookContext,
            toolsUsed.toList(),
            startTime
        )

        if (resolvedCacheKey != null && finalResult.success && finalResult.content != null) {
            try {
                responseCache?.put(
                    resolvedCacheKey,
                    CachedResponse(
                        content = finalResult.content,
                        toolsUsed = finalResult.toolsUsed
                    )
                )
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "Failed to cache response" }
            }
        }

        return finalResult
    }

    private suspend fun resolveCache(command: AgentCommand, startTime: Long): CacheLookupResult {
        if (responseCache == null || !isCacheable(command)) return CacheLookupResult(cacheKey = null, cachedResult = null)

        val toolNames = (toolCallbacks + mcpToolCallbacks()).map { it.name }
        val key = CacheKeyBuilder.buildKey(command, toolNames)
        try {
            responseCache.get(key)?.let { cached ->
                logger.debug { "Cache hit for request" }
                agentMetrics.recordCacheHit(key)
                return CacheLookupResult(
                    cacheKey = key,
                    cachedResult = AgentResult.success(
                        content = cached.content,
                        toolsUsed = cached.toolsUsed,
                        durationMs = nowMs() - startTime
                    )
                )
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Cache lookup failed, proceeding without cache" }
        }
        agentMetrics.recordCacheMiss(key)
        return CacheLookupResult(cacheKey = key, cachedResult = null)
    }

    private suspend fun attemptFallback(command: AgentCommand, originalResult: AgentResult): AgentResult {
        return runSuspendCatchingNonCancellation {
            val error = Exception(originalResult.errorMessage ?: "Agent execution failed")
            val fallbackResult = fallbackStrategy?.execute(command, error)
            if (fallbackResult != null) {
                logger.info { "Fallback succeeded, using fallback response" }
                fallbackResult
            } else {
                originalResult
            }
        }.getOrElse { e ->
            logger.warn(e) { "Fallback strategy failed, using original error" }
            originalResult
        }
    }

    private fun isCacheable(command: AgentCommand): Boolean {
        return (command.temperature ?: defaultTemperature) <= cacheableTemperature
    }

    private data class CacheLookupResult(
        val cacheKey: String?,
        val cachedResult: AgentResult?
    )
}
