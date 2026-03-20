package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.routing.ModelRouter
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.cache.CacheMetricsRecorder
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.resilience.CircuitBreaker
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.response.ResponseFilterChain
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.agent.budget.CostCalculator
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.metrics.SlaMetrics
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.canary.SystemPromptPostProcessor
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.tool.ToolOutputSanitizer
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.impl.UserMemoryInjectionHook
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.mcp.McpToolAvailabilityChecker
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.memory.DefaultConversationManager
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.rag.QueryRouter
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.support.runSuspendCatchingNonCancellation
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.LocalToolFilter
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.prompt.ChatOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

private val logger = KotlinLogging.logger {}

/**
 * Spring AI 기반 에이전트 실행기 — 전체 파이프라인의 메인 진입점.
 *
 * 요청 흐름:
 * Guard -> Hook(BeforeStart) -> ReAct Loop(LLM <-> Tool) -> Output Guard -> Hook(AfterComplete) -> 응답
 *
 * ReAct 패턴 구현:
 * - Guard: 5단계 가드레일 파이프라인 (fail-close)
 * - Hook: 라이프사이클 확장 포인트 (fail-open)
 * - Tool: Spring AI Function Calling + [ToolSelector] 필터링
 * - Memory: 대화 컨텍스트 관리 ([ConversationManager])
 * - RAG: 검색 증강 생성 컨텍스트 주입
 * - Metrics: [AgentMetrics]를 통한 관측성
 *
 * ## Agent Modes
 * - [AgentMode.STANDARD]: Tool 없이 단일 LLM 호출
 * - [AgentMode.REACT]: Tool Calling을 포함하는 ReAct 루프 실행
 * - [AgentMode.STREAMING]: 실시간 스트리밍 응답 (executeStream)
 *
 * @see AgentExecutor 이 클래스가 구현하는 인터페이스
 * @see AgentExecutionCoordinator 캐시/폴백/단계별 실행 조율
 * @see ManualReActLoopExecutor ReAct 루프 본체
 * @see ToolCallOrchestrator 병렬 도구 실행 오케스트레이터
 * @see ExecutionResultFinalizer 출력 가드 및 최종화
 */
class SpringAiAgentExecutor(
    private val chatClient: ChatClient,
    private val chatModelProvider: ChatModelProvider? = null,
    private val properties: AgentProperties,
    private val localTools: List<LocalTool> = emptyList(),
    private val localToolFilters: List<LocalToolFilter> = emptyList(),
    private val toolCallbacks: List<ToolCallback> = emptyList(),
    private val toolSelector: ToolSelector? = null,
    private val guard: RequestGuard? = null,
    private val hookExecutor: HookExecutor? = null,
    private val memoryStore: MemoryStore? = null,
    private val mcpToolCallbacks: () -> List<ToolCallback> = { emptyList() },
    private val errorMessageResolver: ErrorMessageResolver = DefaultErrorMessageResolver(),
    private val agentMetrics: AgentMetrics = NoOpAgentMetrics(),
    private val ragPipeline: RagPipeline? = null,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator(),
    private val transientErrorClassifier: (Exception) -> Boolean = ::defaultTransientErrorClassifier,
    private val conversationManager: ConversationManager = DefaultConversationManager(memoryStore, properties),
    private val toolApprovalPolicy: ToolApprovalPolicy? = null,
    private val pendingApprovalStore: PendingApprovalStore? = null,
    private val responseFilterChain: ResponseFilterChain? = null,
    private val circuitBreaker: CircuitBreaker? = null,
    private val responseCache: ResponseCache? = null,
    private val cacheableTemperature: Double = 0.0,
    private val fallbackStrategy: FallbackStrategy? = null,
    private val outputGuardPipeline: OutputGuardPipeline? = null,
    private val intentResolver: IntentResolver? = null,
    private val blockedIntents: Set<String> = emptySet(),
    private val systemPromptPostProcessor: SystemPromptPostProcessor? = null,
    private val toolOutputSanitizer: ToolOutputSanitizer? = null,
    private val tracer: ArcReactorTracer = NoOpArcReactorTracer(),
    private val queryRouter: QueryRouter? = null,
    private val mcpToolAvailabilityChecker: McpToolAvailabilityChecker? = null,
    private val modelRouter: ModelRouter? = null,
    private val slaMetrics: SlaMetrics? = null,
    private val costCalculator: CostCalculator? = null,
    private val cacheMetricsRecorder: CacheMetricsRecorder? = null
) : AgentExecutor {

    // ── 초기화: 컨텍스트 윈도우가 출력 토큰보다 커야 트리밍이 정상 동작한다 ──
    init {
        val llm = properties.llm
        require(llm.maxContextWindowTokens > llm.maxOutputTokens) {
            "maxContextWindowTokens (${llm.maxContextWindowTokens}) " +
                "must be greater than maxOutputTokens (${llm.maxOutputTokens})"
        }
    }

    // ── 내부 협력 객체 초기화 ──
    // 동시 요청 수 제한 세마포어
    private val concurrencySemaphore = Semaphore(properties.concurrency.maxConcurrentRequests)
    // 실행 단위(run) 컨텍스트 관리 — HookContext, toolsUsed 등 바인딩
    private val runContextManager = AgentRunContextManager()
    // 시스템 프롬프트 조립 (RAG 컨텍스트, 응답 형식 지시 포함)
    private val systemPromptBuilder = SystemPromptBuilder(postProcessor = systemPromptPostProcessor)
    // RAG 검색 파이프라인 래퍼 — 쿼리 라우팅·타임아웃·메트릭 처리
    private val ragContextRetriever = RagContextRetriever(
        enabled = properties.rag.enabled,
        topK = properties.rag.topK,
        rerankEnabled = properties.rag.rerankEnabled,
        ragPipeline = ragPipeline,
        retrievalTimeoutMs = properties.rag.retrievalTimeoutMs,
        metrics = agentMetrics,
        queryRouter = queryRouter,
        complexTopK = properties.rag.adaptiveRouting.complexTopK
    )
    // 에러 분류기 — 일시적(transient) vs 영구(permanent) 에러 판별
    private val agentErrorPolicy = AgentErrorPolicy(transientErrorClassifier)
    // 구조화된 응답(JSON 등) 검증 및 재시도 수리
    private val structuredResponseRepairer = StructuredResponseRepairer(
        errorMessageResolver = errorMessageResolver,
        resolveChatClient = ::resolveChatClient
    )
    // LLM 호출 옵션 팩토리 (temperature, maxOutputTokens 등)
    private val chatOptionsFactory = ChatOptionsFactory(
        defaultTemperature = properties.llm.temperature,
        maxOutputTokens = properties.llm.maxOutputTokens,
        googleSearchRetrievalEnabled = properties.llm.googleSearchRetrievalEnabled,
        topP = properties.llm.topP,
        frequencyPenalty = properties.llm.frequencyPenalty,
        presencePenalty = properties.llm.presencePenalty
    )
    // ChatClient RequestSpec 조립기
    private val promptRequestSpecBuilder = PromptRequestSpecBuilder()
    // Tool 선택·준비 계획 — ToolSelector 필터링, MCP 콜백 병합, 최대 Tool 수 제한
    private val toolPreparationPlanner = ToolPreparationPlanner(
        localTools = localTools,
        toolCallbacks = toolCallbacks,
        mcpToolCallbacks = mcpToolCallbacks,
        toolSelector = toolSelector,
        maxToolsPerRequest = properties.maxToolsPerRequest,
        fallbackToolTimeoutMs = properties.concurrency.toolCallTimeoutMs,
        localToolFilters = localToolFilters,
        mcpToolAvailabilityChecker = mcpToolAvailabilityChecker
    )
    // 대화 메시지 트리밍 — 컨텍스트 윈도우 초과 시 오래된 메시지 제거
    private val messageTrimmer = ConversationMessageTrimmer(
        maxContextWindowTokens = properties.llm.maxContextWindowTokens,
        outputReserveTokens = properties.llm.maxOutputTokens,
        tokenEstimator = tokenEstimator
    )
    // 병렬 도구 실행 오케스트레이터 — Hook·승인·메트릭 처리 포함
    private val toolCallOrchestrator = ToolCallOrchestrator(
        toolCallTimeoutMs = properties.concurrency.toolCallTimeoutMs,
        hookExecutor = hookExecutor,
        toolApprovalPolicy = toolApprovalPolicy,
        pendingApprovalStore = pendingApprovalStore,
        agentMetrics = agentMetrics,
        toolOutputSanitizer = toolOutputSanitizer,
        maxToolOutputLength = properties.mcp.security.maxToolOutputLength,
        requesterAwareToolNames = properties.toolEnrichment.requesterAwareToolNames,
        toolResultCacheProperties = properties.toolResultCache,
        tokenEstimator = tokenEstimator,
        maxContextWindowTokens = properties.llm.maxContextWindowTokens
    )
    // LLM 호출 재시도 실행기 — CircuitBreaker 연동
    private val retryExecutor = RetryExecutor(
        retry = properties.retry,
        circuitBreaker = circuitBreaker,
        isTransientError = agentErrorPolicy::isTransient
    )
    // 수동 ReAct 루프 실행기 — LLM 호출 -> Tool 실행 -> 반복 루프 본체
    private val manualReActLoopExecutor = ManualReActLoopExecutor(
        messageTrimmer = messageTrimmer,
        toolCallOrchestrator = toolCallOrchestrator,
        buildRequestSpec = promptRequestSpecBuilder::create,
        callWithRetry = { block -> retryExecutor.execute(block) },
        buildChatOptions = ::createChatOptions,
        validateAndRepairResponse = structuredResponseRepairer::validateAndRepair,
        recordTokenUsage = { usage, meta -> agentMetrics.recordTokenUsage(usage, meta) },
        tracer = tracer
    )
    // 스트리밍 ReAct 루프 실행기 — 실시간 청크 전송 버전
    private val streamingReActLoopExecutor = StreamingReActLoopExecutor(
        messageTrimmer = messageTrimmer,
        toolCallOrchestrator = toolCallOrchestrator,
        buildRequestSpec = promptRequestSpecBuilder::create,
        callWithRetry = { block -> retryExecutor.execute(block) },
        buildChatOptions = ::createChatOptions,
        recordTokenUsage = { usage, meta -> agentMetrics.recordTokenUsage(usage, meta) },
        agentMetrics = agentMetrics,
        retryProperties = properties.retry,
        isTransientError = { throwable ->
            val effective = if (throwable is Exception) throwable
                else Exception(throwable.message, throwable)
            agentErrorPolicy.isTransient(effective)
        }
    )
    // 스트리밍 완료 후 최종화 — 대화 저장, Output Guard, Hook 실행
    private val streamingCompletionFinalizer = StreamingCompletionFinalizer(
        boundaries = properties.boundaries,
        conversationManager = conversationManager,
        hookExecutor = hookExecutor,
        agentMetrics = agentMetrics,
        outputGuardPipeline = outputGuardPipeline
    )
    // 스트리밍 Flow 라이프사이클 조율 — 에러 처리, RunContext 종료
    private val streamingFlowLifecycleCoordinator = StreamingFlowLifecycleCoordinator(
        streamingCompletionFinalizer = streamingCompletionFinalizer,
        agentMetrics = agentMetrics,
        closeRunContext = runContextManager::close
    )
    // 실행 결과 최종화 — Output Guard, 응답 필터, Citation, 대화 저장
    private val executionResultFinalizer = ExecutionResultFinalizer(
        outputGuardPipeline = outputGuardPipeline,
        responseFilterChain = responseFilterChain,
        boundaries = properties.boundaries,
        conversationManager = conversationManager,
        hookExecutor = hookExecutor,
        errorMessageResolver = errorMessageResolver,
        agentMetrics = agentMetrics,
        citationProperties = properties.citation
    )
    // 사전 실행 검증 — Guard 파이프라인, Hook, Intent 해석
    private val preExecutionResolver = PreExecutionResolver(
        guard = guard,
        hookExecutor = hookExecutor,
        intentResolver = intentResolver,
        blockedIntents = blockedIntents,
        agentMetrics = agentMetrics,
        tracer = tracer
    )
    // 스트리밍 실행 조율 — 동시성 제어, Guard, RAG, 스트리밍 ReAct 루프 통합
    private val streamingExecutionCoordinator = StreamingExecutionCoordinator(
        concurrencySemaphore = concurrencySemaphore,
        requestTimeoutMs = properties.concurrency.requestTimeoutMs,
        maxToolCallsLimit = properties.maxToolCalls,
        preExecutionResolver = preExecutionResolver,
        conversationManager = conversationManager,
        ragContextRetriever = ragContextRetriever,
        systemPromptBuilder = systemPromptBuilder,
        toolPreparationPlanner = toolPreparationPlanner,
        resolveChatClient = ::resolveChatClient,
        resolveIntentAllowedTools = ::resolveIntentAllowedTools,
        streamingReActLoopExecutor = streamingReActLoopExecutor,
        errorMessageResolver = errorMessageResolver,
        agentErrorPolicy = agentErrorPolicy,
        agentMetrics = agentMetrics
    )
    // 실행 실패 핸들러 — 에러 코드 매핑, Hook 실행, 메트릭 기록
    private val executionFailureHandler = AgentExecutionFailureHandler(
        errorMessageResolver = errorMessageResolver,
        hookExecutor = hookExecutor,
        agentMetrics = agentMetrics
    )
    // 에이전트 실행 조율기 — 캐시 조회, 단계별 실행, 폴백 전략 적용
    private val agentExecutionCoordinator = AgentExecutionCoordinator(
        responseCache = responseCache,
        cacheableTemperature = cacheableTemperature,
        defaultTemperature = properties.llm.temperature,
        maxToolCallsLimit = properties.maxToolCalls,
        fallbackStrategy = fallbackStrategy,
        agentMetrics = agentMetrics,
        costCalculator = costCalculator,
        cacheMetricsRecorder = cacheMetricsRecorder,
        semanticSimilarityThreshold = properties.cache.semantic.similarityThreshold,
        modelRouter = modelRouter,
        toolCallbacks = toolCallbacks,
        mcpToolCallbacks = mcpToolCallbacks,
        conversationManager = conversationManager,
        selectAndPrepareTools = toolPreparationPlanner::prepareForPrompt,
        retrieveRagContext = ragContextRetriever::retrieve,
        executeWithTools = ::executeWithTools,
        finalizeExecution = { result, command, hookContext, tools, startTime ->
            executionResultFinalizer.finalize(
                result = result,
                command = command,
                hookContext = hookContext,
                toolsUsed = tools,
                startTime = startTime,
                attemptLongerResponse = ::attemptLongerResponse
            )
        },
        checkGuardAndHooks = preExecutionResolver::checkGuardAndHooks,
        resolveIntent = { command, hookContext -> preExecutionResolver.resolveIntent(command, hookContext) }
    )

    /**
     * 에이전트 요청의 메인 실행 메서드.
     *
     * 실행 순서:
     * 1. RunContext 열기 (HookContext, toolsUsed 바인딩)
     * 2. Tracing span 시작
     * 3. 동시성 세마포어 획득 후 타임아웃 내에서 [AgentExecutionCoordinator.execute] 위임
     * 4. 예외 발생 시 에러 코드 분류 후 [AgentExecutionFailureHandler]로 위임
     *
     * @param command 에이전트 실행 명령 (시스템 프롬프트, 사용자 메시지, 모드 등)
     * @return 실행 결과 — 성공/실패 상태, 응답 내용, 사용된 도구 목록
     * @see AgentCommand
     * @see AgentResult
     * @see AgentExecutionCoordinator
     */
    override suspend fun execute(command: AgentCommand): AgentResult {
        // ── 단계 1: RunContext 초기화 ──
        val startTime = System.currentTimeMillis()
        val toolsUsed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val runContext = runContextManager.open(command, toolsUsed)
        val hookContext = runContext.hookContext
        enrichMetadataWithModelInfo(hookContext, command)

        // ── 단계 2: Tracing span 시작 ──
        val spanAttrs = buildMap<String, String> {
            put("session.id", command.metadata["sessionId"]?.toString().orEmpty())
            put("agent.mode", (command.metadata["mode"] ?: command.mode.name.lowercase()).toString())
            if (properties.tracing.includeUserId) {
                put("user.id", command.userId ?: "anonymous")
            }
        }
        val requestSpan = tracer.startSpan("arc.agent.request", spanAttrs)
        try {
            // ── 단계 3: 동시성 세마포어 획득 + 타임아웃 내 실행 ──
            val queueStart = System.nanoTime()
            val result = concurrencySemaphore.withPermit {
                val queueWaitMs = (System.nanoTime() - queueStart) / 1_000_000
                hookContext.metadata["queueWaitMs"] = queueWaitMs
                recordStageTiming(hookContext, "queue_wait", queueWaitMs)
                agentMetrics.recordStageLatency("queue_wait", queueWaitMs, command.metadata)
                executeWithRequestTimeout(properties.concurrency.requestTimeoutMs) {
                    agentExecutionCoordinator.execute(command, hookContext, toolsUsed, startTime)
                }
            }
            if (!result.success) {
                requestSpan.setAttribute("error.code", result.errorCode?.name ?: "UNKNOWN")
                result.errorMessage?.let { requestSpan.setAttribute("error.message", it.take(500)) }
            }
            return result
        // ── 단계 4: 예외 처리 — 각 예외 유형별 에러 코드 분류 ──
        } catch (e: BlockedIntentException) {
            logger.info { "Blocked intent: ${e.intentName}" }
            requestSpan.setError(e)
            return executionFailureHandler.handle(AgentErrorCode.GUARD_REJECTED, e, hookContext, startTime)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "Request timed out after ${properties.concurrency.requestTimeoutMs}ms" }
            requestSpan.setError(e)
            return executionFailureHandler.handle(AgentErrorCode.TIMEOUT, e, hookContext, startTime)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Agent execution failed" }
            requestSpan.setError(e)
            return executionFailureHandler.handle(agentErrorPolicy.classify(e), e, hookContext, startTime)
        } finally {
            val durationMs = System.currentTimeMillis() - startTime
            val channel = command.metadata["channel"]?.toString() ?: "unknown"
            slaMetrics?.recordE2eLatency(durationMs, channel)
            requestSpan.close()
            runContextManager.close()
        }
    }

    /** 요청 전체 타임아웃을 적용합니다. 0 이하이면 타임아웃 없이 실행합니다. */
    private suspend fun <T> executeWithRequestTimeout(
        requestTimeoutMs: Long,
        block: suspend () -> T
    ): T {
        if (requestTimeoutMs <= 0L) {
            return block()
        }
        return withTimeout(requestTimeoutMs) {
            block()
        }
    }

    /**
     * 출력이 너무 짧을 때 추가 LLM 호출로 더 긴 응답을 시도합니다.
     *
     * [StructuredResponseRepairer]와 동일한 재시도 패턴을 사용합니다.
     *
     * @param shortContent 이전의 짧은 응답 내용
     * @param minChars 최소 요구 글자 수
     * @param command 원본 에이전트 명령
     * @return 더 긴 응답 텍스트, 실패 시 null
     */
    private suspend fun attemptLongerResponse(
        shortContent: String,
        minChars: Int,
        command: AgentCommand
    ): String? {
        return runSuspendCatchingNonCancellation {
            val retryPrompt = """
                Your previous response was too short (${shortContent.length} chars, minimum $minChars chars).
                Please provide a more detailed response while staying faithful to the original user request.

                Original user request:
                ${command.userPrompt}

                Previous short response:
                $shortContent
            """.trimIndent()
            val activeChatClient = resolveChatClient(command)
            val response = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                activeChatClient
                    .prompt()
                    .user(retryPrompt)
                    .call()
                    .chatResponse()
            }
            response?.results?.firstOrNull()?.output?.text
        }.getOrElse { e ->
            logger.warn(e) { "Longer response retry failed" }
            null
        }
    }

    /**
     * 스트리밍 ReAct 루프 — [execute]와 동일한 파이프라인을 실시간 스트리밍으로 실행.
     *
     * 실행 순서:
     * 1. Guard + Hook 검증
     * 2. ChatResponse 청크 단위로 LLM 응답 스트리밍
     * 3. 텍스트 청크를 호출자에게 실시간 emit
     * 4. 스트리밍된 ChatResponse에서 Tool Call 감지
     * 5. BeforeToolCallHook / AfterToolCallHook 포함하여 도구 실행
     * 6. Tool 결과로 LLM 재스트리밍 -> Tool Call이 없을 때까지 반복
     * 7. 대화 히스토리 저장, 메트릭 기록, AfterAgentComplete Hook 실행
     *
     * @param command 에이전트 실행 명령
     * @return 응답 텍스트 청크의 Flow
     * @see StreamingExecutionCoordinator
     * @see StreamingFlowLifecycleCoordinator
     */
    override fun executeStream(command: AgentCommand): Flow<String> = flow {
        val startTime = System.currentTimeMillis()
        val toolsUsed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val runContext = runContextManager.open(command, toolsUsed)
        val hookContext = runContext.hookContext
        enrichMetadataWithModelInfo(hookContext, command)
        var state = StreamingExecutionState()

        try {
            state = streamingExecutionCoordinator.execute(
                command = command,
                hookContext = hookContext,
                toolsUsed = toolsUsed,
                emit = { chunk -> emit(chunk) }
            )
        } finally {
            streamingFlowLifecycleCoordinator.finalize(
                command = command,
                hookContext = hookContext,
                toolsUsed = toolsUsed,
                state = state,
                startTime = startTime,
                emit = { marker -> emit(marker) }
            )
        }
    }

    /** HookContext 메타데이터에 모델/프로바이더 정보를 설정합니다. */
    private fun enrichMetadataWithModelInfo(hookContext: HookContext, command: AgentCommand) {
        val provider = chatModelProvider?.defaultProvider() ?: properties.llm.defaultProvider
        hookContext.metadata.putIfAbsent("model", command.model ?: provider)
        hookContext.metadata.putIfAbsent("provider", provider)
    }

    /** command와 Tool 유무에 따라 LLM 호출 옵션(ChatOptions)을 생성합니다. */
    private fun createChatOptions(command: AgentCommand, hasTools: Boolean): ChatOptions {
        val fallbackProvider = chatModelProvider?.defaultProvider() ?: properties.llm.defaultProvider
        return chatOptionsFactory.create(
            command = command,
            hasTools = hasTools,
            fallbackProvider = fallbackProvider
        )
    }

    /**
     * command의 model 필드에 따라 적절한 ChatClient를 반환합니다.
     *
     * model이 미지정이거나 chatModelProvider가 없으면 기본 chatClient로 폴백합니다.
     */
    private fun resolveChatClient(command: AgentCommand): ChatClient {
        if (command.model == null || chatModelProvider == null) {
            return chatClient
        }
        return chatModelProvider.getChatClient(command.model)
    }

    /** Intent 해석 결과에서 허용된 Tool 이름 목록을 추출합니다. */
    private fun resolveIntentAllowedTools(command: AgentCommand): Set<String>? {
        val raw = command.metadata["intentAllowedTools"] ?: return null
        val parsed = when (raw) {
            is Collection<*> -> raw.filterIsInstance<String>().toSet()
            is Array<*> -> raw.filterIsInstance<String>().toSet()
            is String -> setOf(raw)
            else -> null
        }
        return parsed
    }

    /**
     * 수동 ReAct 루프로 Tool과 함께 LLM을 실행합니다.
     *
     * Tool이 있으면 Spring AI의 내부 Tool 실행을 비활성화하고 루프를 직접 관리합니다.
     * 이를 통해 다음을 제어합니다:
     * - maxToolCalls 제한 적용
     * - 각 Tool 호출 전 BeforeToolCallHook 실행
     * - 각 Tool 호출 후 AfterToolCallHook 실행
     * - Tool별 메트릭 기록
     *
     * @see ManualReActLoopExecutor
     * @see ToolCallOrchestrator
     */
    private suspend fun executeWithTools(
        command: AgentCommand,
        tools: List<Any>,
        conversationHistory: List<Message>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        ragContext: String? = null
    ): AgentResult {
        try {
            val maxToolCalls = minOf(command.maxToolCalls, properties.maxToolCalls).coerceAtLeast(0)
            val allowedTools = resolveIntentAllowedTools(command)
            val forcedToolContext = if (maxToolCalls > 0) {
                maybeExecuteForcedWorkspaceTool(
                    command = command,
                    hookContext = hookContext,
                    toolsUsed = toolsUsed,
                    allowedTools = allowedTools
                )
            } else {
                null
            }
            if (forcedToolContext != null && !forcedToolContext.success) {
                return AgentResult.failure(
                    errorMessage = forcedToolContext.errorMessage
                        ?: forcedToolContext.output
                        ?: AgentErrorCode.TOOL_ERROR.defaultMessage,
                    errorCode = AgentErrorCode.TOOL_ERROR
                )
            }
            val userMemoryContext =
                hookContext.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY]?.toString()
            val effectiveRagContext = mergeRagContext(ragContext, forcedToolContext?.output)
            val systemPrompt = systemPromptBuilder.build(
                command.systemPrompt, effectiveRagContext,
                command.responseFormat,
                command.responseSchema,
                command.userPrompt,
                workspaceToolAlreadyCalled = forcedToolContext != null,
                userMemoryContext = userMemoryContext
            )
            val activeChatClient = resolveChatClient(command)
            return manualReActLoopExecutor.execute(
                command = command,
                activeChatClient = activeChatClient,
                systemPrompt = systemPrompt,
                initialTools = if (forcedToolContext != null) emptyList() else tools,
                conversationHistory = conversationHistory,
                hookContext = hookContext,
                toolsUsed = toolsUsed,
                allowedTools = allowedTools,
                maxToolCalls = maxToolCalls
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "LLM call with tools failed" }
            val errorCode = agentErrorPolicy.classify(e)
            return AgentResult.failure(
                errorMessage = errorMessageResolver.resolve(errorCode, e.message),
                errorCode = errorCode
            )
        }
    }

    /** Workspace 강제 도구 실행이 필요한지 판단하고, 필요하면 선제적으로 실행합니다. */
    private suspend fun maybeExecuteForcedWorkspaceTool(
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        allowedTools: Set<String>?
    ): ToolCallResult? {
        val plan = WorkContextForcedToolPlanner.plan(command.userPrompt) ?: return null
        val callback = (toolCallbacks + mcpToolCallbacks()).firstOrNull { it.name == plan.toolName } ?: return null
        val wrappedTool = ArcToolCallbackAdapter(
            arcCallback = callback,
            fallbackToolTimeoutMs = properties.concurrency.toolCallTimeoutMs
        )
        return toolCallOrchestrator.executeDirectToolCall(
            toolName = plan.toolName,
            toolParams = plan.arguments,
            tools = listOf(wrappedTool),
            hookContext = hookContext,
            toolsUsed = toolsUsed,
            allowedTools = allowedTools
        )
    }

    /** RAG 컨텍스트와 강제 도구 출력을 병합합니다. 둘 다 비어있으면 null을 반환합니다. */
    private fun mergeRagContext(primary: String?, secondary: String?): String? {
        val parts = listOfNotNull(
            primary?.trim()?.takeIf { it.isNotEmpty() },
            secondary?.trim()?.takeIf { it.isNotEmpty() }
        )
        if (parts.isEmpty()) return null
        return parts.joinToString("\n\n")
    }
}
