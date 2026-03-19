package com.arc.reactor.agent.impl

import com.arc.reactor.agent.budget.CostCalculator
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.cache.CacheKeyBuilder
import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.CacheMetricsRecorder
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.cache.SemanticResponseCache
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.support.runSuspendCatchingNonCancellation
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import mu.KotlinLogging
import org.springframework.ai.chat.messages.Message

private val logger = KotlinLogging.logger {}

/**
 * 에이전트 실행 조율기 — 캐시, 폴백, 단계별 실행을 조율합니다.
 *
 * [SpringAiAgentExecutor.execute]에서 Guard/Hook 통과 후 이 클래스로 위임되며,
 * 다음 단계를 순서대로 실행합니다:
 *
 * 1. Guard + Hook 사전 검증 ([PreExecutionResolver])
 * 2. Intent 해석 (선택적)
 * 3. 응답 캐시 조회 (정확 매칭 / 시맨틱 매칭)
 * 4. 대화 히스토리 로드
 * 5. RAG 컨텍스트 검색
 * 6. Tool 선택 및 준비
 * 7. ReAct 루프 실행 ([ManualReActLoopExecutor])
 * 8. 폴백 전략 적용 (실패 시)
 * 9. 실행 결과 최종화 ([ExecutionResultFinalizer])
 * 10. 성공 시 응답 캐시 저장
 *
 * 각 단계의 소요 시간을 HookContext에 기록하여 관측성을 제공합니다.
 *
 * @see SpringAiAgentExecutor 이 조율기를 생성하고 호출하는 상위 클래스
 * @see ExecutionResultFinalizer 출력 가드 및 최종화 위임 대상
 * @see ResponseCache 응답 캐시 (정확/시맨틱)
 * @see FallbackStrategy 실패 시 폴백 전략
 */
internal class AgentExecutionCoordinator(
    private val responseCache: ResponseCache?,
    private val cacheableTemperature: Double,
    private val defaultTemperature: Double,
    private val maxToolCallsLimit: Int = Int.MAX_VALUE,
    private val fallbackStrategy: FallbackStrategy?,
    private val agentMetrics: AgentMetrics,
    private val costCalculator: CostCalculator? = null,
    private val cacheMetricsRecorder: CacheMetricsRecorder? = null,
    private val semanticSimilarityThreshold: Double = 0.92,
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

    /**
     * 에이전트 실행의 핵심 조율 메서드.
     *
     * Guard/Hook 검증 -> 캐시 조회 -> 히스토리 로드 -> RAG 검색 ->
     * Tool 선택 -> ReAct 루프 실행 -> 폴백 -> 최종화 -> 캐시 저장의 전체 흐름을 조율합니다.
     *
     * @param command 에이전트 실행 명령
     * @param hookContext Hook/메트릭용 실행 컨텍스트
     * @param toolsUsed 실행된 도구 이름을 누적하는 리스트
     * @param startTime 실행 시작 타임스탬프 (밀리초)
     * @return 에이전트 실행 결과
     */
    suspend fun execute(
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        startTime: Long
    ): AgentResult {
        // ── 단계 1: Guard + Hook 사전 검증 (실패 시 즉시 반환) ──
        checkGuardAndHooks(command, hookContext, startTime)?.let { return it }
        // ── 단계 2: Intent 해석 ──
        val effectiveCommand = resolveIntent(command, hookContext)
        effectiveCommand.metadata[HookMetadataKeys.INTENT_CATEGORY]?.let {
            hookContext.metadata[HookMetadataKeys.INTENT_CATEGORY] = it
        }

        // ── 단계 3: 응답 캐시 조회 (정확 매칭 / 시맨틱 매칭) ──
        val cacheLookupStart = nowMs()
        val cacheLookup = resolveCache(effectiveCommand, startTime)
        recordStageTiming(hookContext, "cache_lookup", nowMs() - cacheLookupStart)
        agentMetrics.recordStageLatency("cache_lookup", nowMs() - cacheLookupStart, effectiveCommand.metadata)
        cacheLookup.cachedResult?.let { return it }
        val resolvedCacheKey = cacheLookup.cacheKey
        val cacheToolNames = cacheLookup.toolNames

        // ── 단계 4: 대화 히스토리 로드 ──
        val historyLoadStart = nowMs()
        val conversationHistory = conversationManager.loadHistory(effectiveCommand)
        val historyLoadDurationMs = nowMs() - historyLoadStart
        recordStageTiming(hookContext, "history_load", historyLoadDurationMs)
        agentMetrics.recordStageLatency("history_load", historyLoadDurationMs, effectiveCommand.metadata)
        hookContext.metadata[HookMetadataKeys.HISTORY_MESSAGE_COUNT] = conversationHistory.size
        logger.debug { "Loaded ${conversationHistory.size} history messages for session=${effectiveCommand.metadata["sessionId"]}" }

        // ── 단계 5: RAG 컨텍스트 검색 (키워드 사전 필터링으로 불필요한 검색 생략) ──
        val ragStart = nowMs()
        val shouldRetrieveRag = RagRelevanceClassifier.isRagRequired(effectiveCommand)
        val ragResult = if (shouldRetrieveRag) {
            retrieveRagContext(effectiveCommand)
        } else {
            logger.debug { "RAG retrieval skipped: prompt not classified as knowledge query" }
            null
        }
        recordStageTiming(hookContext, "rag_retrieval", nowMs() - ragStart)
        agentMetrics.recordStageLatency("rag_retrieval", nowMs() - ragStart, effectiveCommand.metadata)
        registerRagVerifiedSources(ragResult, hookContext)
        val ragContext = ragResult?.context

        // ── 단계 6: Tool 선택 및 준비 ──
        val toolSelectionStart = nowMs()
        val selectedTools = if (shouldSkipToolSelection(effectiveCommand)) {
            emptyList()
        } else {
            selectAndPrepareTools(effectiveCommand.userPrompt)
        }
        recordStageTiming(hookContext, "tool_selection", nowMs() - toolSelectionStart)
        agentMetrics.recordStageLatency("tool_selection", nowMs() - toolSelectionStart, effectiveCommand.metadata)
        logger.debug { "Selected ${selectedTools.size} tools for execution (mode=${effectiveCommand.mode})" }

        // ── 단계 7: ReAct 루프 실행 ──
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
        recordLoopStageLatency(hookContext, effectiveCommand.metadata, "llm_calls", agentMetrics)
        recordLoopStageLatency(hookContext, effectiveCommand.metadata, "tool_execution", agentMetrics)

        // ── 단계 8: 실패 시 폴백 전략 적용 ──
        if (!result.success && fallbackStrategy != null) {
            val fallbackStart = nowMs()
            val fallbackResult = attemptFallback(effectiveCommand, result)
            recordStageTiming(hookContext, "fallback", nowMs() - fallbackStart)
            agentMetrics.recordStageLatency("fallback", nowMs() - fallbackStart, effectiveCommand.metadata)
            if (fallbackResult !== result) {
                hookContext.metadata[HookMetadataKeys.FALLBACK_USED] = true
            }
            result = fallbackResult
        }

        // ── 단계 9: 실행 결과 최종화 (Output Guard, 응답 필터, Citation) ──
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

        // ── 비용 기록: 토큰 사용량이 있으면 CostCalculator로 USD 비용 산출 후 메트릭에 기록 ──
        recordCostIfAvailable(enrichedFinalResult, hookContext)

        // ── 단계 10: 성공 시 응답 캐시 저장 (빈/차단/저품질 응답은 캐시 오염 방지를 위해 제외) ──
        if (resolvedCacheKey != null && enrichedFinalResult.success
            && !enrichedFinalResult.content.isNullOrBlank()
            && enrichedFinalResult.metadata["blockReason"] == null
            && !isLowQualityResponse(enrichedFinalResult)
        ) {
            try {
                val cacheEntry = CachedResponse(
                    content = enrichedFinalResult.content,
                    toolsUsed = enrichedFinalResult.toolsUsed,
                    metadata = filterCacheableMetadata(enrichedFinalResult.metadata)
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

    /**
     * 응답 캐시를 조회합니다.
     *
     * 정확 매칭을 먼저 시도하고, [SemanticResponseCache]인 경우 시맨틱 매칭도 수행합니다.
     * 캐시 적중 시 AgentResult로 변환하여 즉시 반환합니다.
     */
    private suspend fun resolveCache(command: AgentCommand, startTime: Long): CacheLookupResult {
        if (responseCache == null || !isCacheable(command)) {
            return CacheLookupResult(cacheKey = null, cachedResult = null, toolNames = emptyList())
        }

        val toolNames = (toolCallbacks + mcpToolCallbacks()).map { it.name }
        val key = CacheKeyBuilder.buildKey(command, toolNames)
        try {
            val hit = lookupCacheEntry(responseCache, command, key, toolNames)
            if (hit != null) return cacheHitResult(key, hit, startTime, toolNames)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Cache lookup failed, proceeding without cache" }
        }
        agentMetrics.recordCacheMiss(key)
        cacheMetricsRecorder?.recordMiss()
        return CacheLookupResult(cacheKey = key, cachedResult = null, toolNames = toolNames)
    }

    /**
     * 정확 매칭과 시맨틱 매칭을 순서대로 시도하여 캐시 엔트리를 조회합니다.
     *
     * @return 캐시 적중 시 [CachedResponse], 미적중 시 null
     */
    private suspend fun lookupCacheEntry(
        cache: ResponseCache,
        command: AgentCommand,
        key: String,
        toolNames: List<String>
    ): CachedResponse? {
        val exact = cache.get(key)
        if (exact != null) return handleCacheHit(key, exact, "Exact")

        if (cache is SemanticResponseCache) {
            val semantic = cache.getSemantic(command, toolNames, key)
            if (semantic != null) return handleSemanticCacheHit(key, semantic)
        }
        return null
    }

    /** 정확 캐시 적중 시 메트릭을 기록하고 캐시 응답을 반환합니다. */
    private fun handleCacheHit(key: String, cached: CachedResponse, label: String): CachedResponse {
        logger.debug { "$label cache hit for request" }
        agentMetrics.recordExactCacheHit(key)
        cacheMetricsRecorder?.recordExactHit()
        return cached
    }

    /** 시맨틱 캐시 적중 시 메트릭을 기록하고 캐시 응답을 반환합니다. */
    private fun handleSemanticCacheHit(key: String, cached: CachedResponse): CachedResponse {
        logger.debug { "Semantic cache hit for request" }
        agentMetrics.recordSemanticCacheHit(key)
        cacheMetricsRecorder?.recordSemanticHit(semanticSimilarityThreshold)
        return cached
    }

    private fun cacheHitResult(
        cacheKey: String,
        cached: CachedResponse,
        startTime: Long,
        toolNames: List<String>
    ): CacheLookupResult {
        val restoredMetadata = LinkedHashMap(cached.metadata)
        restoredMetadata["cacheHit"] = true
        return CacheLookupResult(
            cacheKey = cacheKey,
            cachedResult = AgentResult(
                success = true,
                content = cached.content,
                toolsUsed = cached.toolsUsed,
                durationMs = nowMs() - startTime,
                metadata = restoredMetadata
            ),
            toolNames = toolNames
        )
    }

    /** 폴백 전략을 실행합니다. 폴백도 실패하면 원본 에러 결과를 그대로 반환합니다. */
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


    /** 토큰 사용량과 모델 정보가 있으면 비용을 계산하여 메트릭에 기록한다. */
    private fun recordCostIfAvailable(result: AgentResult, hookContext: HookContext) {
        val calc = costCalculator ?: return
        val usage = result.tokenUsage ?: return
        val model = hookContext.metadata["model"]?.toString() ?: return
        val cost = calc.calculateCost(model, usage.promptTokens, usage.completionTokens)
        if (cost.estimatedCostUsd > 0.0) {
            agentMetrics.recordRequestCost(cost.estimatedCostUsd, model, hookContext.metadata.toMap())
        }
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

    /** temperature가 캐시 임계값 이하인 경우에만 캐시 가능으로 판단합니다. */
    private fun isCacheable(command: AgentCommand): Boolean {
        return (command.temperature ?: defaultTemperature) <= cacheableTemperature
    }

    /** STANDARD 모드이거나 maxToolCalls가 0이면 Tool 선택을 건너뜁니다. */
    private fun shouldSkipToolSelection(command: AgentCommand): Boolean {
        if (command.mode == AgentMode.STANDARD) return true
        return effectiveMaxToolCalls(command) == 0
    }

    private fun effectiveMaxToolCalls(command: AgentCommand): Int {
        return minOf(command.maxToolCalls, maxToolCallsLimit).coerceAtLeast(0)
    }


    /** 캐시 저장 전 응답 품질 검증. 저품질 응답은 캐시하지 않는다. */
    private fun isLowQualityResponse(result: AgentResult): Boolean {
        val content = result.content ?: return true
        if (content.length < MIN_CACHEABLE_CONTENT_LENGTH) return true
        return FAILURE_PATTERNS.any { content.contains(it) }
    }

    /** 캐시에 저장할 사용자 대면 메타데이터만 필터링한다. stageTimings, toolSignals 등 임시 운영 데이터는 제외. */
    private fun filterCacheableMetadata(metadata: Map<String, Any>): Map<String, Any> {
        return metadata.filterKeys { it in CACHEABLE_METADATA_KEYS }
    }

    private data class CacheLookupResult(
        val cacheKey: String?,
        val cachedResult: AgentResult?,
        val toolNames: List<String>
    )

    companion object {
        /** 캐시 저장 최소 응답 길이 (이하 = 저품질로 판단) */
        private const val MIN_CACHEABLE_CONTENT_LENGTH = 30

        /** 실패/불확실 응답 패턴. 이 문자열이 포함된 응답은 캐시하지 않는다. */
        private val FAILURE_PATTERNS = listOf(
            "검증 가능한 출처를 찾지 못해",
            "확정할 수 없습니다",
            "찾지 못했습니다",
            "I cannot",
            "I don't know",
            "사용할 수 없습니다"
        )

        /** 캐시에 보존할 사용자 대면 메타데이터 키. 운영/디버깅 전용 키는 캐시하지 않는다. */
        private val CACHEABLE_METADATA_KEYS = setOf(
            "grounded",
            "answerMode",
            "verifiedSourceCount",
            "verifiedSources",
            "freshness",
            "retrievedAt",
            "deliveryAcknowledged",
            "delivery"
        )
    }
}
