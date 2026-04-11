package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.routing.AgentModeResolver
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
import com.arc.reactor.agent.budget.StepBudgetTracker
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.ExecutionStage
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.agent.metrics.recordError
import io.micrometer.core.instrument.MeterRegistry
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
 * - [AgentMode.PLAN_EXECUTE]: 2단계 계획-실행 (계획 JSON → 순차 도구 실행)
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
    private val approvalContextResolver: com.arc.reactor.approval.ApprovalContextResolver? = null,
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
    private val agentModeResolver: AgentModeResolver? = null,
    private val slaMetrics: SlaMetrics? = null,
    private val costCalculator: CostCalculator? = null,
    private val cacheMetricsRecorder: CacheMetricsRecorder? = null,
    private val meterRegistry: MeterRegistry? = null,
    /**
     * R247: R245/R246 `execution.error{stage="tool_call"}` 메트릭을 자동 기록하기 위한 수집기.
     *
     * `ToolPreparationPlanner`, `ToolCallOrchestrator`, 그리고 강제 Workspace Tool 실행 경로에
     * 생성되는 모든 `ArcToolCallbackAdapter`에 전달된다. 기본값은 `NoOpEvaluationMetricsCollector`
     * 이므로 사용자가 `arc.reactor.evaluation.metrics.enabled=true`를 설정하고
     * `MicrometerEvaluationMetricsCollector` 빈을 주입할 때만 실제 값이 수집된다.
     */
    private val evaluationMetricsCollector: EvaluationMetricsCollector = NoOpEvaluationMetricsCollector
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
        mcpToolAvailabilityChecker = mcpToolAvailabilityChecker,
        evaluationMetricsCollector = evaluationMetricsCollector
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
        mcpToolCallbackProvider = mcpToolCallbacks,
        toolResultCacheProperties = properties.toolResultCache,
        tokenEstimator = tokenEstimator,
        maxContextWindowTokens = properties.llm.maxContextWindowTokens,
        approvalContextResolver = approvalContextResolver,
        evaluationMetricsCollector = evaluationMetricsCollector
    )
    // LLM 호출 재시도 실행기 — CircuitBreaker 연동
    // R248: evaluationMetricsCollector로 최종 실패 시 execution.error{stage=llm_call} 자동 기록
    private val retryExecutor = RetryExecutor(
        retry = properties.retry,
        circuitBreaker = circuitBreaker,
        isTransientError = agentErrorPolicy::isTransient,
        evaluationMetricsCollector = evaluationMetricsCollector
        // errorStage는 기본값 LLM_CALL 사용 (RetryExecutor의 유일한 용도)
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
    // 계획-실행 전략 — 3단계 (계획 JSON 생성 → 검증 → 순차 도구 실행)
    private val planExecuteStrategy = PlanExecuteStrategy(
        toolCallOrchestrator = toolCallOrchestrator,
        buildRequestSpec = promptRequestSpecBuilder::create,
        callWithRetry = { block -> retryExecutor.execute(block) },
        buildChatOptions = ::createChatOptions,
        systemPromptBuilder = systemPromptBuilder,
        toolApprovalPolicy = toolApprovalPolicy,
        // R254: JSON 계획 파싱 실패 → PARSING stage 자동 기록
        evaluationMetricsCollector = evaluationMetricsCollector,
        // R309: generatePlan / synthesize / directAnswer LLM 호출 토큰 사용량 기록
        recordTokenUsage = { usage, meta -> agentMetrics.recordTokenUsage(usage, meta) }
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
        citationProperties = properties.citation,
        costCalculator = costCalculator ?: CostCalculator()
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
        agentMetrics = agentMetrics,
        createBudgetTracker = ::createBudgetTracker
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
        agentModeResolver = agentModeResolver,
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
        resolveIntent = { command, hookContext -> preExecutionResolver.resolveIntent(command, hookContext) },
        // R253: R247에서 이미 주입받은 collector를 CACHE stage 자동 기록에 전달
        evaluationMetricsCollector = evaluationMetricsCollector
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
        // R271: CopyOnWriteArrayList → mutableListOf 변경 — 단일 코루틴 sequential 접근만 발생.
        // 병렬 도구 실행은 awaitAll() 후 collectParallelResults에서 단일 스레드로 add하므로
        // 동시 접근 없음. CopyOnWriteArrayList의 O(n) per add overhead 제거.
        val toolsUsed = mutableListOf<String>()
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
            var result = concurrencySemaphore.withPermit {
                val queueWaitMs = (System.nanoTime() - queueStart) / 1_000_000
                hookContext.metadata["queueWaitMs"] = queueWaitMs
                recordStageTiming(hookContext, "queue_wait", queueWaitMs)
                agentMetrics.recordStageLatency("queue_wait", queueWaitMs, command.metadata)
                executeWithRequestTimeout(properties.concurrency.requestTimeoutMs) {
                    agentExecutionCoordinator.execute(command, hookContext, toolsUsed, startTime)
                }
            }
            // R206: 빈 응답 자동 재시도 (최대 2회) — Gemini 간헐적 빈 응답 대응
            // R208: 재시도 시 "minimal prompt" 플래그 주입 → SystemPromptBuilder가 R202/R203/
            // SELF_IDENTITY 같은 부가 섹션을 생략한 축약 프롬프트 생성 → Gemini safety/length
            // 임계치 회피. B4 "개발 환경 세팅 방법" deterministic empty 해결 목적.
            for (retryAttempt in 1..EMPTY_RESPONSE_MAX_RETRIES) {
                val isEmptyResponse = !result.success &&
                    result.content?.contains("응답을 생성하지 못했습니다") == true
                if (!isEmptyResponse) break
                logger.info {
                    "빈 응답 감지, 재시도 $retryAttempt/$EMPTY_RESPONSE_MAX_RETRIES " +
                        "(runId=${hookContext.runId})"
                }
                // 이전 실행에서 설정된 상태를 정리하여 재시도 오염을 방지한다.
                hookContext.metadata.remove("blockReason")
                (hookContext.verifiedSources as? MutableList)?.clear()
                toolsUsed.clear()
                // R208: 재시도 시 minimal prompt 요청 플래그 설정
                hookContext.metadata[MINIMAL_PROMPT_RETRY_KEY] = true
                result = concurrencySemaphore.withPermit {
                    executeWithRequestTimeout(properties.concurrency.requestTimeoutMs) {
                        agentExecutionCoordinator.execute(command, hookContext, toolsUsed, startTime)
                    }
                }
            }
            hookContext.metadata.remove(MINIMAL_PROMPT_RETRY_KEY)
            if (!result.success) {
                requestSpan.setAttribute("error.code", result.errorCode?.name ?: "UNKNOWN")
                result.errorMessage?.let { requestSpan.setAttribute("error.message", it.take(500)) }
            }
            return result
        // ── 단계 4: 예외 처리 — 각 예외 유형별 에러 코드 분류 ──
        } catch (e: BlockedIntentException) {
            logger.info { "차단된 인텐트: ${e.intentName}" }
            requestSpan.setError(e)
            return executionFailureHandler.handle(AgentErrorCode.GUARD_REJECTED, e, hookContext, startTime)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "요청 타임아웃: ${properties.concurrency.requestTimeoutMs}ms 경과" }
            requestSpan.setError(e)
            return executionFailureHandler.handle(AgentErrorCode.TIMEOUT, e, hookContext, startTime)
        } catch (e: Exception) {
            e.throwIfCancellation()
            // R255: 최상위 catch-all — 하위 stage(TOOL_CALL/LLM_CALL/GUARD/HOOK/OUTPUT_GUARD/
            // PARSING/MEMORY/CACHE)에 의해 이미 기록되지 않은 분류 불가 예외를 OTHER stage로 기록.
            // 이로써 9/9 stage 자동 기록 100% 달성 — 운영자는 `stage=other` 기록을 보고
            // "어느 stage에도 분류되지 않은 예외"를 drill-down하여 새 stage 필요 여부 판단 가능.
            evaluationMetricsCollector.recordError(ExecutionStage.OTHER, e)
            logger.error(e) { "에이전트 실행 실패" }
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
            logger.warn(e) { "더 긴 응답 재시도 실패" }
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
        // R271: 위 execute() 함수와 동일한 이유로 mutableListOf 사용. Streaming Flow 또한
        // 단일 코루틴 sequential 흐름이며 toolsUsed는 collectParallelResults에서만 add됨.
        val toolsUsed = mutableListOf<String>()
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
        val modelId = command.model
            ?: properties.llm.defaultModel
            ?: provider
        hookContext.metadata.putIfAbsent("model", modelId)
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
            val forcedToolContext = executeForcedToolIfNeeded(
                command, hookContext, toolsUsed, allowedTools, maxToolCalls
            )
            if (forcedToolContext != null && !forcedToolContext.success) {
                return AgentResult.failure(
                    errorMessage = forcedToolContext.errorMessage
                        ?: forcedToolContext.output
                        ?: AgentErrorCode.TOOL_ERROR.defaultMessage,
                    errorCode = AgentErrorCode.TOOL_ERROR
                )
            }
            val systemPrompt = buildSystemPromptForExecution(command, hookContext, ragContext, forcedToolContext)
            // 강제 도구 실행 후에도 도구 목록을 유지하여 LLM이 추가 도구 호출 가능하게 함
            // (기존: forcedToolContext != null → emptyList → LLM이 텍스트로만 응답하는 버그)
            val effectiveTools = tools
            return dispatchToStrategy(
                command, effectiveTools, conversationHistory, hookContext,
                toolsUsed, allowedTools, maxToolCalls, systemPrompt
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "도구 포함 LLM 호출 실패" }
            val errorCode = agentErrorPolicy.classify(e)
            return AgentResult.failure(
                errorMessage = errorMessageResolver.resolve(errorCode, e.message),
                errorCode = errorCode
            )
        }
    }

    /** maxToolCalls > 0이면 강제 Workspace 도구 실행을 시도한다. */
    private suspend fun executeForcedToolIfNeeded(
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        allowedTools: Set<String>?,
        maxToolCalls: Int
    ): ToolCallResult? {
        if (maxToolCalls <= 0) return null
        return maybeExecuteForcedWorkspaceTool(
            command = command,
            hookContext = hookContext,
            toolsUsed = toolsUsed,
            allowedTools = allowedTools
        )
    }

    /** 시스템 프롬프트를 조립한다. RAG 컨텍스트, 사용자 메모리, 강제 도구 출력을 통합한다. */
    private fun buildSystemPromptForExecution(
        command: AgentCommand,
        hookContext: HookContext,
        ragContext: String?,
        forcedToolContext: ToolCallResult?
    ): String {
        val userMemoryContext =
            hookContext.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY]?.toString()
        val effectiveRagContext = mergeRagContext(ragContext, forcedToolContext?.output)
        val requesterContext = buildRequesterContext(hookContext.metadata)
        val enrichedMemoryContext = listOfNotNull(userMemoryContext, requesterContext)
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
        // R208: 재시도 경로에서 minimal prompt 요청 플래그 확인
        val minimalPromptRetry = hookContext.metadata[MINIMAL_PROMPT_RETRY_KEY] == true
        return systemPromptBuilder.build(
            command.systemPrompt, effectiveRagContext,
            command.responseFormat,
            command.responseSchema,
            command.userPrompt,
            workspaceToolAlreadyCalled = forcedToolContext != null,
            userMemoryContext = enrichedMemoryContext,
            minimalPromptRetry = minimalPromptRetry
        )
    }

    /** 요청자 신원 정보를 시스템 프롬프트 컨텍스트로 변환한다. */
    private fun buildRequesterContext(metadata: Map<String, Any>): String? {
        val email = metadata["requesterEmail"]?.toString()?.takeIf { it.isNotBlank() }
        val accountId = metadata["requesterAccountId"]?.toString()?.takeIf { it.isNotBlank() }
        val displayName = metadata["requesterDisplayName"]?.toString()?.takeIf { it.isNotBlank() }
        if (email == null && accountId == null) return null
        val sb = StringBuilder("[Requester Identity]\n")
        displayName?.let { sb.append("displayName: $it\n") }
        email?.let { sb.append("email: $it\n") }
        accountId?.let { sb.append("jiraAccountId: $it\n") }
        sb.append("IMPORTANT: 개인화 도구(jira_my_open_issues, bitbucket_my_authored_prs 등) 호출 시 위 정보를 requesterEmail 또는 assigneeAccountId 파라미터에 자동으로 사용하라. 사용자에게 이메일을 다시 물어보지 마라.")
        return sb.toString()
    }

    /** 에이전트 모드에 따라 적절한 실행 전략(PLAN_EXECUTE vs ReAct)으로 위임한다. */
    private suspend fun dispatchToStrategy(
        command: AgentCommand,
        effectiveTools: List<Any>,
        conversationHistory: List<Message>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        allowedTools: Set<String>?,
        maxToolCalls: Int,
        systemPrompt: String
    ): AgentResult {
        val activeChatClient = resolveChatClient(command)
        return if (command.mode == AgentMode.PLAN_EXECUTE && effectiveTools.isNotEmpty()) {
            planExecuteStrategy.execute(
                command = command,
                activeChatClient = activeChatClient,
                systemPrompt = systemPrompt,
                tools = effectiveTools,
                conversationHistory = conversationHistory,
                hookContext = hookContext,
                toolsUsed = toolsUsed,
                maxToolCalls = maxToolCalls,
                budgetTracker = createBudgetTracker()
            )
        } else {
            manualReActLoopExecutor.execute(
                command = command,
                activeChatClient = activeChatClient,
                systemPrompt = systemPrompt,
                initialTools = effectiveTools,
                conversationHistory = conversationHistory,
                hookContext = hookContext,
                toolsUsed = toolsUsed,
                allowedTools = allowedTools,
                maxToolCalls = maxToolCalls,
                budgetTracker = createBudgetTracker()
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
            fallbackToolTimeoutMs = properties.concurrency.toolCallTimeoutMs,
            evaluationCollector = evaluationMetricsCollector
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

    /**
     * 예산 추적이 활성화되어 있으면 요청 스코프 [StepBudgetTracker]를 생성한다.
     * `budget.enabled=false`(기본)이면 null을 반환하여 기존 동작을 유지한다.
     */
    private fun createBudgetTracker(): StepBudgetTracker? {
        val budget = properties.budget
        if (!budget.enabled) return null
        return StepBudgetTracker(
            maxTokens = budget.maxTokensPerRequest,
            softLimitPercent = budget.softLimitPercent,
            meterRegistry = meterRegistry
        )
    }

    companion object {
        /**
         * R206: 빈 응답 자동 재시도 최대 횟수.
         * Gemini가 간헐적으로 empty content를 반환하는 variance를 커버하기 위해 2회로 증가.
         * (R199 이전: 1회) B4 "개발 환경 세팅 방법" 같은 시나리오에서 ~50% 실패율 개선 목적.
         */
        private const val EMPTY_RESPONSE_MAX_RETRIES = 2

        /**
         * R208: 빈 응답 재시도 시 `SystemPromptBuilder`가 축약 프롬프트를 생성하도록 알리는
         * HookContext metadata 키. 재시도 경로에서만 true로 설정되어 R202/R203/SELF_IDENTITY 등
         * 부가 섹션을 생략한 minimal prompt가 생성되며, 재시도 완료 후 제거된다.
         */
        internal const val MINIMAL_PROMPT_RETRY_KEY = "arc.reactor.internal.minimalPromptRetry"
    }
}
