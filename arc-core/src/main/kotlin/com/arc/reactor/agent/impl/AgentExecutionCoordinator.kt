package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.cache.CacheKeyBuilder
import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.cache.SemanticResponseCache
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.response.VerifiedSource
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
    private val maxToolCallsLimit: Int = Int.MAX_VALUE,
    private val fallbackStrategy: FallbackStrategy?,
    private val agentMetrics: AgentMetrics,
    private val toolCallbacks: List<ToolCallback>,
    private val mcpToolCallbacks: () -> List<ToolCallback>,
    private val conversationManager: ConversationManager,
    private val selectAndPrepareTools: (String) -> List<Any>,
    private val retrieveRagContext: suspend (AgentCommand) -> RagContext?,
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
    private val resolveIntent: suspend (AgentCommand, HookContext) -> AgentCommand,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    suspend fun execute(
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        startTime: Long
    ): AgentResult {
        checkGuardAndHooks(command, hookContext, startTime)?.let { return it }
        val effectiveCommand = resolveIntent(command, hookContext)
        effectiveCommand.metadata["intentCategory"]?.let { hookContext.metadata["intentCategory"] = it }

        val cacheLookupStart = nowMs()
        val cacheLookup = resolveCache(effectiveCommand, startTime)
        recordStageTiming(hookContext, "cache_lookup", nowMs() - cacheLookupStart)
        agentMetrics.recordStageLatency("cache_lookup", nowMs() - cacheLookupStart, effectiveCommand.metadata)
        cacheLookup.cachedResult?.let { return it }
        val resolvedCacheKey = cacheLookup.cacheKey
        val cacheToolNames = cacheLookup.toolNames

        val historyLoadStart = nowMs()
        val conversationHistory = conversationManager.loadHistory(effectiveCommand)
        recordStageTiming(hookContext, "history_load", nowMs() - historyLoadStart)
        agentMetrics.recordStageLatency("history_load", nowMs() - historyLoadStart, effectiveCommand.metadata)

        val ragStart = nowMs()
        val ragResult = retrieveRagContext(effectiveCommand)
        recordStageTiming(hookContext, "rag_retrieval", nowMs() - ragStart)
        agentMetrics.recordStageLatency("rag_retrieval", nowMs() - ragStart, effectiveCommand.metadata)
        registerRagVerifiedSources(ragResult, hookContext)
        val ragContext = ragResult?.context

        val toolSelectionStart = nowMs()
        val selectedTools = if (shouldSkipToolSelection(effectiveCommand)) {
            emptyList()
        } else {
            selectAndPrepareTools(effectiveCommand.userPrompt)
        }
        recordStageTiming(hookContext, "tool_selection", nowMs() - toolSelectionStart)
        agentMetrics.recordStageLatency("tool_selection", nowMs() - toolSelectionStart, effectiveCommand.metadata)
        logger.debug { "Selected ${selectedTools.size} tools for execution (mode=${effectiveCommand.mode})" }

        val agentLoopStart = nowMs()
        var result = executeWithTools(
            effectiveCommand,
            selectedTools,
            conversationHistory,
            hookContext,
            toolsUsed,
            ragContext
        )
        recordStageTiming(hookContext, "agent_loop", nowMs() - agentLoopStart)
        agentMetrics.recordStageLatency("agent_loop", nowMs() - agentLoopStart, effectiveCommand.metadata)
        recordLoopStageLatency(hookContext, effectiveCommand.metadata, "llm_calls")
        recordLoopStageLatency(hookContext, effectiveCommand.metadata, "tool_execution")

        if (!result.success && fallbackStrategy != null) {
            val fallbackStart = nowMs()
            val fallbackResult = attemptFallback(effectiveCommand, result)
            recordStageTiming(hookContext, "fallback", nowMs() - fallbackStart)
            agentMetrics.recordStageLatency("fallback", nowMs() - fallbackStart, effectiveCommand.metadata)
            if (fallbackResult !== result) {
                hookContext.metadata["fallbackUsed"] = true
            }
            result = fallbackResult
        }

        val finalizerStart = nowMs()
        val finalResult = finalizeExecution(
            result,
            effectiveCommand,
            hookContext,
            toolsUsed.toList(),
            startTime
        )
        val finalizerDurationMs = nowMs() - finalizerStart
        recordStageTiming(hookContext, "finalizer", finalizerDurationMs)
        agentMetrics.recordStageLatency("finalizer", finalizerDurationMs, effectiveCommand.metadata)
        val enrichedFinalResult = withStageTimingsMetadata(finalResult, hookContext)

        if (resolvedCacheKey != null && enrichedFinalResult.success && enrichedFinalResult.content != null) {
            try {
                val cacheEntry = CachedResponse(
                    content = enrichedFinalResult.content,
                    toolsUsed = enrichedFinalResult.toolsUsed
                )
                when (val cache = responseCache) {
                    is SemanticResponseCache -> cache.putSemantic(
                        command = effectiveCommand,
                        toolNames = cacheToolNames,
                        exactKey = resolvedCacheKey,
                        response = cacheEntry
                    )
                    null -> {}
                    else -> cache.put(resolvedCacheKey, cacheEntry)
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "Failed to cache response" }
            }
        }

        return enrichedFinalResult
    }

    private suspend fun resolveCache(command: AgentCommand, startTime: Long): CacheLookupResult {
        if (responseCache == null || !isCacheable(command)) {
            return CacheLookupResult(cacheKey = null, cachedResult = null, toolNames = emptyList())
        }

        val toolNames = (toolCallbacks + mcpToolCallbacks()).map { it.name }
        val key = CacheKeyBuilder.buildKey(command, toolNames)
        try {
            when (val cache = responseCache) {
                is SemanticResponseCache -> {
                    val exact = cache.get(key)
                    if (exact != null) {
                        logger.debug { "Exact cache hit for request" }
                        agentMetrics.recordExactCacheHit(key)
                        return cacheHitResult(key, exact, startTime, toolNames)
                    }

                    val semantic = cache.getSemantic(command, toolNames, key)
                    if (semantic != null) {
                        logger.debug { "Semantic cache hit for request" }
                        agentMetrics.recordSemanticCacheHit(key)
                        return cacheHitResult(key, semantic, startTime, toolNames)
                    }
                }
                else -> {
                    val exact = cache.get(key)
                    if (exact != null) {
                        logger.debug { "Exact cache hit for request" }
                        agentMetrics.recordExactCacheHit(key)
                        return cacheHitResult(key, exact, startTime, toolNames)
                    }
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Cache lookup failed, proceeding without cache" }
        }
        agentMetrics.recordCacheMiss(key)
        return CacheLookupResult(cacheKey = key, cachedResult = null, toolNames = toolNames)
    }

    private fun cacheHitResult(
        cacheKey: String,
        cached: CachedResponse,
        startTime: Long,
        toolNames: List<String>
    ): CacheLookupResult {
        return CacheLookupResult(
            cacheKey = cacheKey,
            cachedResult = AgentResult.success(
                content = cached.content,
                toolsUsed = cached.toolsUsed,
                durationMs = nowMs() - startTime
            ),
            toolNames = toolNames
        )
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

    private fun recordLoopStageLatency(hookContext: HookContext, metadata: Map<String, Any>, stage: String) {
        val durationMs = readStageTimings(hookContext)[stage] ?: return
        agentMetrics.recordStageLatency(stage, durationMs, metadata)
    }

    private fun withStageTimingsMetadata(result: AgentResult, hookContext: HookContext): AgentResult {
        val stageTimings = readStageTimings(hookContext)
        if (stageTimings.isEmpty()) {
            return result
        }
        val metadata = LinkedHashMap(result.metadata)
        metadata["stageTimings"] = LinkedHashMap(stageTimings)
        return result.copy(metadata = metadata)
    }

    private fun isCacheable(command: AgentCommand): Boolean {
        return (command.temperature ?: defaultTemperature) <= cacheableTemperature
    }

    private fun shouldSkipToolSelection(command: AgentCommand): Boolean {
        if (command.mode == AgentMode.STANDARD) return true
        return effectiveMaxToolCalls(command) == 0
    }

    private fun effectiveMaxToolCalls(command: AgentCommand): Int {
        return minOf(command.maxToolCalls, maxToolCallsLimit).coerceAtLeast(0)
    }

    private fun registerRagVerifiedSources(ragResult: RagContext?, hookContext: HookContext) {
        if (ragResult == null || !ragResult.hasDocuments) return
        for (doc in ragResult.documents) {
            val source = doc.source?.takeIf { it.isNotBlank() } ?: continue
            hookContext.verifiedSources.add(
                VerifiedSource(
                    title = doc.metadata["title"]?.toString()
                        ?: doc.id,
                    url = source,
                    toolName = "rag"
                )
            )
        }
    }

    private data class CacheLookupResult(
        val cacheKey: String?,
        val cachedResult: AgentResult?,
        val toolNames: List<String>
    )
}
