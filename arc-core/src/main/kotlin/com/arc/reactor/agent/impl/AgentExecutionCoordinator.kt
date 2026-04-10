package com.arc.reactor.agent.impl

import com.arc.reactor.agent.budget.CostCalculator
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.ExecutionStage
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.agent.metrics.recordError
import com.arc.reactor.agent.routing.AgentModeResolver
import com.arc.reactor.agent.routing.ModelRouter
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val modelRouter: ModelRouter? = null,
    private val agentModeResolver: AgentModeResolver? = null,
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
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val nowNanos: () -> Long = System::nanoTime,
    /**
     * R253: 캐시 저장/조회 실패를 `execution.error{stage="cache"}`에 자동 기록.
     * 기본값 NoOp으로 backward compat. 캐시는 fail-open으로 swallowing되므로
     * 이 메트릭 없이는 Redis/Caffeine 장애가 조용히 발생한다.
     */
    private val evaluationMetricsCollector: EvaluationMetricsCollector = NoOpEvaluationMetricsCollector
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
        checkGuardAndHooks(command, hookContext, startTime)?.let { return it }
        val afterIntent = resolveIntentStage(command, hookContext)
        val cmd = applyModelRouting(afterIntent, hookContext)

        val cacheLookup = measureStage("cache_lookup", hookContext, cmd) {
            resolveCache(cmd, startTime)
        }
        cacheLookup.cachedResult?.let { cached ->
            // 캐시 히트 응답도 Output Guard를 통과시켜야 한다.
            // Guard 규칙이 캐시 저장 이후 추가/변경될 수 있으므로
            // 캐시 응답이 현재 Guard 정책을 우회하지 않도록 한다.
            toolsUsed.addAll(cached.toolsUsed)
            val guarded = measureStage("finalizer", hookContext, cmd) {
                finalizeExecution(cached, cmd, hookContext, toolsUsed.toList(), startTime)
            }
            return withStageTimingsMetadata(guarded, hookContext)
        }

        val (history, ragCtx) = coroutineScope {
            val historyDeferred = async {
                measureStage("history_load", hookContext, cmd) {
                    loadConversationHistory(cmd, hookContext)
                }
            }
            val ragDeferred = async {
                measureStage("rag_retrieval", hookContext, cmd) {
                    retrieveRag(cmd, hookContext)
                }
            }
            Pair(historyDeferred.await(), ragDeferred.await())
        }
        val tools = measureStage("tool_selection", hookContext, cmd) {
            selectActiveTools(cmd)
        }
        val modeResolved = applyModeResolution(cmd, tools, hookContext)
        var result = measureStage("agent_loop", hookContext, modeResolved) {
            executeAgentLoop(modeResolved, tools, history, hookContext, toolsUsed, ragCtx)
        }
        result = applyFallbackIfNeeded(modeResolved, result, hookContext)

        // 중복 도구 호출을 사용자 메트릭에서 제거한다 — LLM이 같은 도구를 여러 번 요청해도
        // 최종 응답에는 고유 이름 1회만 표시되어 메트릭 왜곡을 방지한다.
        val deduplicatedToolsUsed = toolsUsed.distinct()
        val finalResult = measureStage("finalizer", hookContext, modeResolved) {
            finalizeExecution(result, modeResolved, hookContext, deduplicatedToolsUsed, startTime)
        }
        val enriched = withStageTimingsMetadata(finalResult, hookContext)
        recordCostIfAvailable(enriched, hookContext)
        storeCacheIfEligible(cacheLookup, modeResolved, enriched)
        return enriched
    }

    // ── 파이프라인 단계 메서드 ─────────────────────────────

    /** 단계별 소요 시간을 측정하여 HookContext와 메트릭에 기록한다. */
    private suspend inline fun <T> measureStage(
        stage: String,
        hookContext: HookContext,
        command: AgentCommand,
        block: () -> T
    ): T {
        val startNanos = nowNanos()
        return block().also {
            val durationMs = (nowNanos() - startNanos) / 1_000_000
            recordStageTiming(hookContext, stage, durationMs)
            agentMetrics.recordStageLatency(stage, durationMs, command.metadata)
        }
    }

    /** Intent를 해석하고 카테고리 메타데이터를 HookContext에 전파한다. */
    private suspend fun resolveIntentStage(
        command: AgentCommand,
        hookContext: HookContext
    ): AgentCommand {
        val effective = resolveIntent(command, hookContext)
        effective.metadata[HookMetadataKeys.INTENT_CATEGORY]?.let {
            hookContext.metadata[HookMetadataKeys.INTENT_CATEGORY] = it
        }
        return effective
    }

    /**
     * 모델 라우팅을 적용하여 요청 복잡도에 따라 최적 모델을 선택한다.
     *
     * 라우터가 없으면 원본 command를 반환한다.
     * 라우팅 결과(모델, 사유, 복잡도)를 HookContext 메타데이터에 기록한다.
     */
    private fun applyModelRouting(
        command: AgentCommand,
        hookContext: HookContext
    ): AgentCommand {
        val router = modelRouter ?: return command
        val selection = router.route(command)
        hookContext.metadata["modelUsed"] = selection.modelId
        hookContext.metadata["routingReason"] = selection.reason
        selection.complexityScore?.let {
            hookContext.metadata["complexityScore"] = it
        }
        logger.debug {
            "모델 라우팅 적용: model=${selection.modelId}, " +
                "reason=${selection.reason}"
        }
        return command.copy(model = selection.modelId)
    }

    /**
     * 자동 모드 선택을 적용하여 쿼리 복잡도에 맞는 실행 모드를 결정한다.
     *
     * 리졸버가 없으면 원본 command를 반환한다.
     * 결정된 모드를 HookContext 메타데이터에 기록한다.
     */
    private suspend fun applyModeResolution(
        command: AgentCommand,
        tools: List<Any>,
        hookContext: HookContext
    ): AgentCommand {
        val resolver = agentModeResolver ?: return command
        val toolNames = extractToolNames(tools)
        val resolved = resolver.resolve(command, toolNames)
        if (resolved != command.mode) {
            hookContext.metadata["modeResolved"] = resolved.name
            logger.debug {
                "모드 자동 선택 적용: ${command.mode} → $resolved"
            }
            return command.copy(mode = resolved)
        }
        return command
    }

    /** 도구 목록에서 도구 이름을 추출한다. */
    private fun extractToolNames(tools: List<Any>): List<String> {
        return tools.mapNotNull { tool ->
            when (tool) {
                is org.springframework.ai.tool.ToolCallback ->
                    tool.toolDefinition.name()
                else -> null
            }
        }
    }

    /** 대화 히스토리를 로드하고 메시지 수를 HookContext에 기록한다. */
    private suspend fun loadConversationHistory(
        command: AgentCommand,
        hookContext: HookContext
    ): List<Message> {
        val history = conversationManager.loadHistory(command)
        hookContext.metadata[HookMetadataKeys.HISTORY_MESSAGE_COUNT] = history.size
        logger.debug {
            "대화 히스토리 ${history.size}건 로드 완료: " +
                "session=${command.metadata["sessionId"]}"
        }
        return history
    }

    /** RAG 컨텍스트를 검색한다. 키워드 사전 필터링으로 불필요한 검색을 생략한다. */
    private suspend fun retrieveRag(
        command: AgentCommand,
        hookContext: HookContext
    ): String? {
        if (!RagRelevanceClassifier.isRagRequired(command)) {
            logger.debug { "RAG 검색 생략: 지식 질의가 아님" }
            return null
        }
        val ragResult = retrieveRagContext(command)
        registerRagVerifiedSources(ragResult, hookContext)
        return ragResult?.context
    }

    /** 실행 모드와 도구 예산에 따라 활성 도구를 선택한다. */
    private fun selectActiveTools(command: AgentCommand): List<Any> {
        val tools = if (shouldSkipToolSelection(command)) {
            emptyList()
        } else {
            selectAndPrepareTools(command.userPrompt)
        }
        logger.debug {
            "도구 ${tools.size}개 선택 완료 (mode=${command.mode})"
        }
        return tools
    }

    /** ReAct 루프를 실행하고 내부 단계(LLM 호출, 도구 실행) 레이턴시를 기록한다. */
    private suspend fun executeAgentLoop(
        command: AgentCommand,
        tools: List<Any>,
        history: List<Message>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        ragContext: String?
    ): AgentResult {
        val result = executeWithTools(
            command, tools, history, hookContext, toolsUsed, ragContext
        )
        recordLoopStageLatency(hookContext, command.metadata, "llm_calls", agentMetrics)
        recordLoopStageLatency(
            hookContext, command.metadata, "tool_execution", agentMetrics
        )
        return result
    }

    /** 실패 시 폴백 전략을 적용한다. 성공이거나 폴백 미설정이면 원본을 반환한다. */
    private suspend fun applyFallbackIfNeeded(
        command: AgentCommand,
        result: AgentResult,
        hookContext: HookContext
    ): AgentResult {
        if (result.success || fallbackStrategy == null) return result
        val fallbackStartNanos = nowNanos()
        val fallbackResult = attemptFallback(command, result)
        val duration = (nowNanos() - fallbackStartNanos) / 1_000_000
        recordStageTiming(hookContext, "fallback", duration)
        agentMetrics.recordStageLatency("fallback", duration, command.metadata)
        if (fallbackResult !== result) {
            hookContext.metadata[HookMetadataKeys.FALLBACK_USED] = true
        }
        return fallbackResult
    }

    /** 성공·비차단·고품질 응답만 캐시에 저장한다. 캐시 오염 방지. */
    private suspend fun storeCacheIfEligible(
        cacheLookup: CacheLookupResult,
        command: AgentCommand,
        result: AgentResult
    ) {
        val cacheKey = cacheLookup.cacheKey ?: return
        if (!result.success) return
        if (result.content.isNullOrBlank()) return
        if (result.metadata["blockReason"] != null) return
        if (isLowQualityResponse(result)) return

        try {
            val entry = CachedResponse(
                content = result.content,
                toolsUsed = result.toolsUsed,
                metadata = filterCacheableMetadata(result.metadata)
            )
            when (val cache = responseCache) {
                is SemanticResponseCache -> cache.putSemantic(
                    command = command,
                    toolNames = cacheLookup.toolNames,
                    exactKey = cacheKey,
                    response = entry
                )
                null -> {}
                else -> cache.put(cacheKey, entry)
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            // R253: 캐시 저장 실패를 CACHE stage로 기록 (fail-open swallowing 전)
            evaluationMetricsCollector.recordError(ExecutionStage.CACHE, e)
            logger.warn(e) { "응답 캐시 저장 실패" }
        }
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
            // R253: 캐시 조회 실패를 CACHE stage로 기록 (fail-open 폴스루 전)
            evaluationMetricsCollector.recordError(ExecutionStage.CACHE, e)
            logger.warn(e) { "캐시 조회 실패, 캐시 없이 계속 진행" }
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
        logger.debug { "$label 캐시 히트" }
        agentMetrics.recordExactCacheHit(key)
        cacheMetricsRecorder?.recordExactHit()
        return cached
    }

    /** 시맨틱 캐시 적중 시 메트릭을 기록하고 캐시 응답을 반환합니다. */
    private fun handleSemanticCacheHit(key: String, cached: CachedResponse): CachedResponse {
        logger.debug { "시맨틱 캐시 히트" }
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
                logger.info { "폴백 성공, 폴백 응답 사용" }
                fallbackResult
            } else {
                originalResult
            }
        }.getOrElse { e ->
            logger.warn(e) { "폴백 전략 실패, 원본 에러 사용" }
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
        val routingMeta = collectRoutingMetadata(hookContext)
        if (stageTimings.isEmpty() && routingMeta.isEmpty()) {
            return result
        }
        val metadata = LinkedHashMap(result.metadata)
        if (stageTimings.isNotEmpty()) {
            metadata["stageTimings"] = LinkedHashMap(stageTimings)
        }
        metadata.putAll(routingMeta)
        return result.copy(metadata = metadata)
    }

    /** HookContext에서 모델 라우팅 메타데이터를 수집한다. */
    private fun collectRoutingMetadata(
        hookContext: HookContext
    ): Map<String, Any> {
        val meta = LinkedHashMap<String, Any>()
        hookContext.metadata["modelUsed"]?.let { meta["modelUsed"] = it }
        hookContext.metadata["routingReason"]?.let {
            meta["routingReason"] = it
        }
        hookContext.metadata["complexityScore"]?.let {
            meta["complexityScore"] = it
        }
        return meta
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
