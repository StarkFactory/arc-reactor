package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.resilience.CircuitBreaker
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.response.ResponseFilterChain
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.memory.DefaultConversationManager
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.support.runSuspendCatchingNonCancellation
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import mu.KotlinLogging
import org.slf4j.MDC
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.prompt.ChatOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Spring AI-Based Agent Executor
 *
 * ReAct pattern implementation:
 * - Guard: 5-stage guardrail pipeline
 * - Hook: lifecycle extension points
 * - Tool: Spring AI Function Calling with ToolSelector filtering
 * - Memory: conversation context management
 * - RAG: Retrieval-Augmented Generation context injection
 * - Metrics: observability via AgentMetrics
 *
 * ## Agent Modes
 * - [AgentMode.STANDARD]: Single LLM call without tools
 * - [AgentMode.REACT]: LLM with tool calling (Spring AI handles the iteration loop)
 * - [AgentMode.STREAMING]: Planned for future (see executeStream)
 */
class SpringAiAgentExecutor(
    private val chatClient: ChatClient,
    private val chatModelProvider: ChatModelProvider? = null,
    private val properties: AgentProperties,
    private val localTools: List<LocalTool> = emptyList(),
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
    private val blockedIntents: Set<String> = emptySet()
) : AgentExecutor {

    init {
        val llm = properties.llm
        require(llm.maxContextWindowTokens > llm.maxOutputTokens) {
            "maxContextWindowTokens (${llm.maxContextWindowTokens}) " +
                "must be greater than maxOutputTokens (${llm.maxOutputTokens})"
        }
    }

    private val concurrencySemaphore = Semaphore(properties.concurrency.maxConcurrentRequests)
    private val systemPromptBuilder = SystemPromptBuilder()
    private val ragContextRetriever = RagContextRetriever(
        enabled = properties.rag.enabled,
        topK = properties.rag.topK,
        rerankEnabled = properties.rag.rerankEnabled,
        ragPipeline = ragPipeline
    )
    private val agentErrorPolicy = AgentErrorPolicy(transientErrorClassifier)
    private val structuredResponseRepairer = StructuredResponseRepairer(
        errorMessageResolver = errorMessageResolver,
        resolveChatClient = ::resolveChatClient
    )
    private val chatOptionsFactory = ChatOptionsFactory(
        defaultTemperature = properties.llm.temperature,
        maxOutputTokens = properties.llm.maxOutputTokens,
        googleSearchRetrievalEnabled = properties.llm.googleSearchRetrievalEnabled
    )
    private val promptRequestSpecBuilder = PromptRequestSpecBuilder()
    private val toolPreparationPlanner = ToolPreparationPlanner(
        localTools = localTools,
        toolCallbacks = toolCallbacks,
        mcpToolCallbacks = mcpToolCallbacks,
        toolSelector = toolSelector,
        maxToolsPerRequest = properties.maxToolsPerRequest,
        fallbackToolTimeoutMs = properties.concurrency.toolCallTimeoutMs
    )
    private val messageTrimmer = ConversationMessageTrimmer(
        maxContextWindowTokens = properties.llm.maxContextWindowTokens,
        outputReserveTokens = properties.llm.maxOutputTokens,
        tokenEstimator = tokenEstimator
    )
    private val toolCallOrchestrator = ToolCallOrchestrator(
        toolCallTimeoutMs = properties.concurrency.toolCallTimeoutMs,
        hookExecutor = hookExecutor,
        toolApprovalPolicy = toolApprovalPolicy,
        pendingApprovalStore = pendingApprovalStore,
        agentMetrics = agentMetrics
    )
    private val retryExecutor = RetryExecutor(
        retry = properties.retry,
        circuitBreaker = circuitBreaker,
        isTransientError = agentErrorPolicy::isTransient
    )
    private val manualReActLoopExecutor = ManualReActLoopExecutor(
        messageTrimmer = messageTrimmer,
        toolCallOrchestrator = toolCallOrchestrator,
        buildRequestSpec = promptRequestSpecBuilder::create,
        callWithRetry = { block -> retryExecutor.execute(block) },
        buildChatOptions = ::createChatOptions,
        validateAndRepairResponse = structuredResponseRepairer::validateAndRepair,
        recordTokenUsage = agentMetrics::recordTokenUsage
    )
    private val streamingReActLoopExecutor = StreamingReActLoopExecutor(
        messageTrimmer = messageTrimmer,
        toolCallOrchestrator = toolCallOrchestrator,
        buildRequestSpec = promptRequestSpecBuilder::create,
        callWithRetry = { block -> retryExecutor.execute(block) },
        buildChatOptions = ::createChatOptions
    )
    private val streamingCompletionFinalizer = StreamingCompletionFinalizer(
        boundaries = properties.boundaries,
        conversationManager = conversationManager,
        hookExecutor = hookExecutor,
        agentMetrics = agentMetrics
    )
    private val executionResultFinalizer = ExecutionResultFinalizer(
        outputGuardPipeline = outputGuardPipeline,
        responseFilterChain = responseFilterChain,
        boundaries = properties.boundaries,
        conversationManager = conversationManager,
        hookExecutor = hookExecutor,
        errorMessageResolver = errorMessageResolver,
        agentMetrics = agentMetrics
    )
    private val preExecutionResolver = PreExecutionResolver(
        guard = guard,
        hookExecutor = hookExecutor,
        intentResolver = intentResolver,
        blockedIntents = blockedIntents,
        agentMetrics = agentMetrics
    )
    private val agentExecutionCoordinator = AgentExecutionCoordinator(
        responseCache = responseCache,
        cacheableTemperature = cacheableTemperature,
        defaultTemperature = properties.llm.temperature,
        fallbackStrategy = fallbackStrategy,
        agentMetrics = agentMetrics,
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
        resolveIntent = preExecutionResolver::resolveIntent
    )

    override suspend fun execute(command: AgentCommand): AgentResult {
        val startTime = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        val toolsUsed = java.util.concurrent.CopyOnWriteArrayList<String>()

        // Set MDC context for structured logging
        MDC.put("runId", runId)
        MDC.put("userId", command.userId ?: "anonymous")
        command.metadata["sessionId"]?.toString()?.let { MDC.put("sessionId", it) }

        // Create hook context
        val hookContext = HookContext(
            runId = runId,
            userId = command.userId ?: "anonymous",
            userPrompt = command.userPrompt,
            channel = command.metadata["channel"]?.toString(),
            toolsUsed = toolsUsed
        )
        hookContext.metadata.putAll(command.metadata)

        try {
            return concurrencySemaphore.withPermit {
                withTimeout(properties.concurrency.requestTimeoutMs) {
                    agentExecutionCoordinator.execute(command, hookContext, toolsUsed, startTime)
                }
            }
        } catch (e: BlockedIntentException) {
            logger.info { "Blocked intent: ${e.intentName}" }
            return handleFailureWithHook(AgentErrorCode.GUARD_REJECTED, e, hookContext, startTime)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "Request timed out after ${properties.concurrency.requestTimeoutMs}ms" }
            return handleFailureWithHook(AgentErrorCode.TIMEOUT, e, hookContext, startTime)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Agent execution failed" }
            return handleFailureWithHook(agentErrorPolicy.classify(e), e, hookContext, startTime)
        } finally {
            MDC.remove("runId")
            MDC.remove("userId")
            MDC.remove("sessionId")
        }
    }

    private suspend fun handleFailureWithHook(
        errorCode: AgentErrorCode,
        exception: Exception,
        hookContext: HookContext,
        startTime: Long
    ): AgentResult {
        val failResult = AgentResult.failure(
            errorMessage = errorMessageResolver.resolve(errorCode, exception.message),
            errorCode = errorCode,
            durationMs = System.currentTimeMillis() - startTime
        )
        try {
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(success = false, errorMessage = failResult.errorMessage)
            )
        } catch (hookEx: Exception) {
            hookEx.throwIfCancellation()
            logger.error(hookEx) { "AfterAgentComplete hook failed during error handling" }
        }
        agentMetrics.recordExecution(failResult)
        return failResult
    }

    /**
     * Make one additional LLM call requesting a longer response.
     * Follows the same retry pattern used by [StructuredResponseRepairer].
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
            val response = kotlinx.coroutines.runInterruptible {
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
     * Full Streaming ReAct Loop.
     *
     * Implements the same ReAct pattern as [execute] but with real-time streaming:
     * 1. Guard + Hook checks
     * 2. Stream LLM response via ChatResponse chunks (not plain text)
     * 3. Emit text chunks to the caller in real-time
     * 4. Detect tool calls from streamed ChatResponse
     * 5. Execute tools with BeforeToolCallHook / AfterToolCallHook
     * 6. Re-stream LLM with tool results → repeat until no tool calls
     * 7. Save conversation history, record metrics, run AfterAgentComplete hook
     */
    override fun executeStream(command: AgentCommand): Flow<String> = flow {
        val startTime = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        val toolsUsed = java.util.concurrent.CopyOnWriteArrayList<String>()

        // Set MDC context for structured logging
        MDC.put("runId", runId)
        MDC.put("userId", command.userId ?: "anonymous")
        command.metadata["sessionId"]?.toString()?.let { MDC.put("sessionId", it) }

        val hookContext = HookContext(
            runId = runId,
            userId = command.userId ?: "anonymous",
            userPrompt = command.userPrompt,
            channel = command.metadata["channel"]?.toString(),
            toolsUsed = toolsUsed
        )
        hookContext.metadata.putAll(command.metadata)

        var streamSuccess = false
        var streamErrorCode: AgentErrorCode? = null
        var streamErrorMessage: String? = null
        var streamStarted = false
        val collectedContent = StringBuilder()
        var lastIterationContent = StringBuilder()

        try {
            concurrencySemaphore.withPermit {
                withTimeout(properties.concurrency.requestTimeoutMs) {
                    // 1. Guard check
                    preExecutionResolver.checkGuard(command)?.let { rejection ->
                        agentMetrics.recordGuardRejection(
                            stage = rejection.stage ?: "unknown",
                            reason = rejection.reason
                        )
                        emit(StreamEventMarker.error(rejection.reason))
                        return@withTimeout
                    }

                    // 2. Before hooks
                    preExecutionResolver.checkBeforeHooks(hookContext)?.let { rejection ->
                        emit(StreamEventMarker.error(rejection.reason))
                        return@withTimeout
                    }

                    // 3. Intent resolution (after guard/hooks)
                    val effectiveCommand = preExecutionResolver.resolveIntent(command)

                    // Reject structured formats in streaming mode
                    if (effectiveCommand.responseFormat != ResponseFormat.TEXT) {
                        emit(StreamEventMarker.error("Structured ${effectiveCommand.responseFormat} output is not supported in streaming mode"))
                        return@withTimeout
                    }

                    streamStarted = true

                    // 4. Setup (conversation via ConversationManager)
                    val conversationHistory = conversationManager.loadHistory(effectiveCommand)
                    val ragContext = ragContextRetriever.retrieve(effectiveCommand)
                    val systemPrompt = systemPromptBuilder.build(
                        effectiveCommand.systemPrompt, ragContext,
                        effectiveCommand.responseFormat, effectiveCommand.responseSchema
                    )
                    val selectedTools = if (effectiveCommand.mode == AgentMode.STANDARD) {
                        emptyList()
                    } else {
                        toolPreparationPlanner.prepareForPrompt(effectiveCommand.userPrompt)
                    }

                    logger.debug { "Streaming ReAct: ${selectedTools.size} tools selected (mode=${effectiveCommand.mode})" }

                    // 5. Run streaming ReAct loop
                    val activeChatClient = resolveChatClient(effectiveCommand)
                    val maxToolCallLimit = minOf(effectiveCommand.maxToolCalls, properties.maxToolCalls).coerceAtLeast(1)
                    val allowedTools = resolveIntentAllowedTools(effectiveCommand)

                    val loopResult = streamingReActLoopExecutor.execute(
                        command = effectiveCommand,
                        activeChatClient = activeChatClient,
                        systemPrompt = systemPrompt,
                        initialTools = selectedTools,
                        conversationHistory = conversationHistory,
                        hookContext = hookContext,
                        toolsUsed = toolsUsed,
                        allowedTools = allowedTools,
                        maxToolCalls = maxToolCallLimit,
                        emit = { chunk -> emit(chunk) }
                    )
                    streamSuccess = loopResult.success
                    collectedContent.append(loopResult.collectedContent)
                    lastIterationContent = StringBuilder(loopResult.lastIterationContent)
                }
            }
        } catch (e: BlockedIntentException) {
            logger.info { "Blocked intent in streaming: ${e.intentName}" }
            streamErrorCode = AgentErrorCode.GUARD_REJECTED
            streamErrorMessage = e.message
            emit(StreamEventMarker.error(streamErrorMessage.orEmpty()))
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "Streaming request timed out after ${properties.concurrency.requestTimeoutMs}ms" }
            streamErrorCode = AgentErrorCode.TIMEOUT
            streamErrorMessage = errorMessageResolver.resolve(AgentErrorCode.TIMEOUT, e.message)
            emit(StreamEventMarker.error(streamErrorMessage.orEmpty()))
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Streaming execution failed" }
            val errorCode = agentErrorPolicy.classify(e)
            streamErrorCode = errorCode
            streamErrorMessage = errorMessageResolver.resolve(errorCode, e.message)
            emit(StreamEventMarker.error(streamErrorMessage.orEmpty()))
        } finally {
            streamingCompletionFinalizer.finalize(
                command = command,
                hookContext = hookContext,
                streamStarted = streamStarted,
                streamSuccess = streamSuccess,
                collectedContent = collectedContent.toString(),
                lastIterationContent = lastIterationContent.toString(),
                streamErrorMessage = streamErrorMessage,
                toolsUsed = toolsUsed.toList(),
                emit = { marker -> emit(marker) }
            )

            recordStreamingMetrics(
                streamSuccess, collectedContent.toString(), streamErrorMessage,
                streamErrorCode, toolsUsed.toList(), startTime
            )

            MDC.remove("runId")
            MDC.remove("userId")
            MDC.remove("sessionId")
        }
    }

    private fun recordStreamingMetrics(
        success: Boolean,
        content: String,
        errorMessage: String?,
        errorCode: AgentErrorCode?,
        toolsUsed: List<String>,
        startTime: Long
    ) {
        val durationMs = System.currentTimeMillis() - startTime
        val result = if (success) {
            AgentResult.success(content = content, toolsUsed = toolsUsed, durationMs = durationMs)
        } else {
            AgentResult.failure(
                errorMessage = errorMessage ?: "Streaming failed",
                errorCode = errorCode ?: AgentErrorCode.UNKNOWN,
                durationMs = durationMs
            )
        }
        agentMetrics.recordStreamingExecution(result)
    }

    private fun createChatOptions(command: AgentCommand, hasTools: Boolean): ChatOptions {
        val fallbackProvider = chatModelProvider?.defaultProvider() ?: properties.llm.defaultProvider
        return chatOptionsFactory.create(
            command = command,
            hasTools = hasTools,
            fallbackProvider = fallbackProvider
        )
    }

    /**
     * Resolves the ChatClient based on the command's model field.
     * Falls back to the default chatClient when model is unspecified
     * or chatModelProvider is unavailable.
     */
    private fun resolveChatClient(command: AgentCommand): ChatClient {
        if (command.model == null || chatModelProvider == null) {
            return chatClient
        }
        return chatModelProvider.getChatClient(command.model)
    }

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
     * Execute tools with Spring AI ChatClient using a manual ReAct loop.
     *
     * When tools are present, disables Spring AI's internal tool execution
     * and manages the loop directly. This enables:
     * - maxToolCalls enforcement
     * - BeforeToolCallHook invocation before each tool call
     * - AfterToolCallHook invocation after each tool call
     * - Per-tool metrics recording
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
            val maxToolCalls = minOf(command.maxToolCalls, properties.maxToolCalls).coerceAtLeast(1)
            val allowedTools = resolveIntentAllowedTools(command)
            val systemPrompt = systemPromptBuilder.build(
                command.systemPrompt, ragContext,
                command.responseFormat, command.responseSchema
            )
            val activeChatClient = resolveChatClient(command)
            return manualReActLoopExecutor.execute(
                command = command,
                activeChatClient = activeChatClient,
                systemPrompt = systemPrompt,
                initialTools = tools,
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

}
