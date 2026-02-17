package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.cache.CacheKeyBuilder
import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.resilience.CircuitBreaker
import com.arc.reactor.resilience.CircuitBreakerOpenException
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.response.ResponseFilterChain
import com.arc.reactor.response.ResponseFilterContext
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.memory.DefaultConversationManager
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.model.RagQuery
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
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}
private val httpStatusPattern = Regex("(status|http|error|code)[^a-z0-9]*(429|500|502|503|504)")

/**
 * Default transient error classifier.
 * Determines if an exception is a temporary error worth retrying.
 * Override via SpringAiAgentExecutor constructor for custom classification.
 */
fun defaultTransientErrorClassifier(e: Exception): Boolean {
    val message = e.message?.lowercase() ?: return false
    return httpStatusPattern.containsMatchIn(message) ||
            message.contains("rate limit") ||
            message.contains("too many requests") ||
            message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("connection refused") ||
            message.contains("connection reset") ||
            message.contains("internal server error") ||
            message.contains("service unavailable") ||
            message.contains("bad gateway")
}

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
    private val structuredOutputValidator = StructuredOutputValidator()
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
                    executeInternal(command, hookContext, toolsUsed, startTime)
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
            return handleFailureWithHook(classifyError(e), e, hookContext, startTime)
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
            logger.error(hookEx) { "AfterAgentComplete hook failed during error handling" }
        }
        agentMetrics.recordExecution(failResult)
        return failResult
    }

    /**
     * Run guard check. Returns rejection result if rejected, null if allowed.
     */
    private suspend fun checkGuard(command: AgentCommand): GuardResult.Rejected? {
        if (guard == null) return null
        val userId = command.userId ?: "anonymous"
        val result = guard.guard(GuardCommand(
            userId = userId,
            text = command.userPrompt,
            systemPrompt = command.systemPrompt
        ))
        return result as? GuardResult.Rejected
    }

    /**
     * Run before-agent-start hooks. Returns rejection if blocked, null if continue.
     */
    private suspend fun checkBeforeHooks(hookContext: HookContext): HookResult.Reject? {
        if (hookExecutor == null) return null
        return hookExecutor.executeBeforeAgentStart(hookContext) as? HookResult.Reject
    }

    private suspend fun executeInternal(
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        startTime: Long
    ): AgentResult {
        checkGuardAndHooks(command, hookContext, startTime)?.let { return it }
        val effectiveCommand = resolveIntent(command)

        // Check response cache (only for cacheable temperature)
        val cacheKey = if (responseCache != null && isCacheable(effectiveCommand)) {
            val toolNames = (toolCallbacks + mcpToolCallbacks()).map { it.name }
            val key = CacheKeyBuilder.buildKey(effectiveCommand, toolNames)
            try {
                responseCache.get(key)?.let { cached ->
                    logger.debug { "Cache hit for request" }
                    agentMetrics.recordCacheHit(key)
                    return AgentResult.success(
                        content = cached.content,
                        toolsUsed = cached.toolsUsed,
                        durationMs = System.currentTimeMillis() - startTime
                    )
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "Cache lookup failed, proceeding without cache" }
            }
            agentMetrics.recordCacheMiss(key)
            key
        } else {
            null
        }

        val conversationHistory = conversationManager.loadHistory(effectiveCommand)
        val ragContext = retrieveRagContext(effectiveCommand)
        val selectedTools = if (effectiveCommand.mode == AgentMode.STANDARD) {
            emptyList()
        } else {
            selectAndPrepareTools(effectiveCommand.userPrompt)
        }
        logger.debug { "Selected ${selectedTools.size} tools for execution (mode=${effectiveCommand.mode})" }

        var result = executeWithTools(
            command = effectiveCommand, tools = selectedTools,
            conversationHistory = conversationHistory,
            hookContext = hookContext, toolsUsed = toolsUsed, ragContext = ragContext
        )

        // Attempt fallback on failure
        if (!result.success && fallbackStrategy != null) {
            result = attemptFallback(effectiveCommand, result)
        }

        val finalResult = finishExecution(result, effectiveCommand, hookContext, toolsUsed, startTime)

        // Save to cache on success
        if (cacheKey != null && finalResult.success && finalResult.content != null) {
            try {
                responseCache?.put(cacheKey, CachedResponse(
                    content = finalResult.content,
                    toolsUsed = finalResult.toolsUsed
                ))
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "Failed to cache response" }
            }
        }

        return finalResult
    }

    private fun isCacheable(command: AgentCommand): Boolean {
        return (command.temperature ?: properties.llm.temperature) <= cacheableTemperature
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

    private suspend fun checkGuardAndHooks(
        command: AgentCommand,
        hookContext: HookContext,
        startTime: Long
    ): AgentResult? {
        checkGuard(command)?.let { rejection ->
            agentMetrics.recordGuardRejection(
                stage = rejection.stage ?: "unknown",
                reason = rejection.reason
            )
            return AgentResult.failure(
                errorMessage = rejection.reason,
                errorCode = AgentErrorCode.GUARD_REJECTED,
                durationMs = System.currentTimeMillis() - startTime
            ).also { agentMetrics.recordExecution(it) }
        }
        checkBeforeHooks(hookContext)?.let { rejection ->
            return AgentResult.failure(
                errorMessage = rejection.reason,
                errorCode = AgentErrorCode.HOOK_REJECTED,
                durationMs = System.currentTimeMillis() - startTime
            ).also { agentMetrics.recordExecution(it) }
        }
        return null
    }

    /**
     * Resolve intent and apply profile to the command.
     *
     * Fail-safe: on any error (except blocked intents), returns the original command.
     */
    private suspend fun resolveIntent(command: AgentCommand): AgentCommand {
        if (intentResolver == null) return command
        try {
            val context = ClassificationContext(
                userId = command.userId,
                channel = command.metadata["channel"]?.toString()
            )
            val resolved = intentResolver.resolve(command.userPrompt, context)
                ?: return command
            if (blockedIntents.contains(resolved.intentName)) {
                throw BlockedIntentException(resolved.intentName)
            }
            return intentResolver.applyProfile(command, resolved)
        } catch (e: BlockedIntentException) {
            throw e
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Intent resolution failed, using original command" }
            return command
        }
    }

    private suspend fun finishExecution(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        startTime: Long
    ): AgentResult {
        // Step 1: Apply output guard pipeline (fail-close, before response filter)
        val guarded = if (result.success && result.content != null && outputGuardPipeline != null) {
            try {
                val guardContext = OutputGuardContext(
                    command = command,
                    toolsUsed = toolsUsed.toList(),
                    durationMs = System.currentTimeMillis() - startTime
                )
                when (val guardResult = outputGuardPipeline.check(result.content, guardContext)) {
                    is OutputGuardResult.Allowed -> {
                        agentMetrics.recordOutputGuardAction("pipeline", "allowed", "")
                        result
                    }
                    is OutputGuardResult.Modified -> {
                        agentMetrics.recordOutputGuardAction(
                            guardResult.stage ?: "unknown", "modified", guardResult.reason
                        )
                        result.copy(content = guardResult.content)
                    }
                    is OutputGuardResult.Rejected -> {
                        agentMetrics.recordOutputGuardAction(
                            guardResult.stage ?: "unknown", "rejected", guardResult.reason
                        )
                        return AgentResult.failure(
                            errorMessage = guardResult.reason,
                            errorCode = AgentErrorCode.OUTPUT_GUARD_REJECTED,
                            durationMs = System.currentTimeMillis() - startTime
                        ).also { agentMetrics.recordExecution(it) }
                    }
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Output guard pipeline failed, rejecting (fail-close)" }
                return AgentResult.failure(
                    errorMessage = "Output guard check failed",
                    errorCode = AgentErrorCode.OUTPUT_GUARD_REJECTED,
                    durationMs = System.currentTimeMillis() - startTime
                ).also { agentMetrics.recordExecution(it) }
            }
        } else {
            result
        }

        // Step 1.5: Apply output boundary check (between output guard and response filter)
        val bounded = if (guarded.success && guarded.content != null) {
            checkOutputBoundary(guarded, command, startTime)
                ?: return AgentResult.failure(
                    errorMessage = errorMessageResolver.resolve(AgentErrorCode.OUTPUT_TOO_SHORT, null),
                    errorCode = AgentErrorCode.OUTPUT_TOO_SHORT,
                    durationMs = System.currentTimeMillis() - startTime
                ).also { agentMetrics.recordExecution(it) }
        } else {
            guarded
        }

        // Step 2: Apply response filter chain (fail-open, after output guard)
        val filtered = if (bounded.success && bounded.content != null && responseFilterChain != null) {
            try {
                val context = ResponseFilterContext(
                    command = command,
                    toolsUsed = toolsUsed.toList(),
                    durationMs = System.currentTimeMillis() - startTime
                )
                val filteredContent = responseFilterChain.apply(bounded.content, context)
                bounded.copy(content = filteredContent)
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "Response filter chain failed, using original content" }
                bounded
            }
        } else {
            bounded
        }

        conversationManager.saveHistory(command, filtered)
        try {
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(
                    success = filtered.success, response = filtered.content,
                    errorMessage = filtered.errorMessage, toolsUsed = toolsUsed
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "AfterAgentComplete hook failed" }
        }
        val finalResult = filtered.copy(durationMs = System.currentTimeMillis() - startTime)
        agentMetrics.recordExecution(finalResult)
        return finalResult
    }

    /**
     * Check output boundary constraints (min/max chars).
     *
     * Returns the (possibly truncated) result, or null if FAIL mode and too short.
     */
    private suspend fun checkOutputBoundary(
        result: AgentResult,
        command: AgentCommand,
        startTime: Long
    ): AgentResult? {
        val boundaries = properties.boundaries
        val content = result.content ?: return result
        val len = content.length

        // Max chars check: truncate if exceeded
        val afterMax = if (boundaries.outputMaxChars > 0 && len > boundaries.outputMaxChars) {
            agentMetrics.recordBoundaryViolation(
                "output_too_long", "truncate", boundaries.outputMaxChars, len
            )
            logger.info { "Output truncated: $len chars exceeds max ${boundaries.outputMaxChars}" }
            result.copy(content = content.take(boundaries.outputMaxChars) + "\n\n[Response truncated]")
        } else {
            result
        }

        // Min chars check
        val effectiveContent = afterMax.content ?: return afterMax
        if (boundaries.outputMinChars <= 0 || effectiveContent.length >= boundaries.outputMinChars) {
            return afterMax
        }

        // Output is too short
        return when (boundaries.outputMinViolationMode) {
            OutputMinViolationMode.WARN -> {
                agentMetrics.recordBoundaryViolation(
                    "output_too_short", "warn", boundaries.outputMinChars, effectiveContent.length
                )
                logger.warn {
                    "Output too short: ${effectiveContent.length} chars " +
                        "(min: ${boundaries.outputMinChars}), passing through (WARN)"
                }
                afterMax
            }
            OutputMinViolationMode.RETRY_ONCE -> {
                agentMetrics.recordBoundaryViolation(
                    "output_too_short", "retry", boundaries.outputMinChars, effectiveContent.length
                )
                logger.info {
                    "Output too short: ${effectiveContent.length} chars " +
                        "(min: ${boundaries.outputMinChars}), retrying once"
                }
                val retried = attemptLongerResponse(effectiveContent, boundaries.outputMinChars, command)
                if (retried != null && retried.length >= boundaries.outputMinChars) {
                    afterMax.copy(content = retried)
                } else {
                    logger.warn { "Retry still too short, falling back to WARN" }
                    afterMax
                }
            }
            OutputMinViolationMode.FAIL -> {
                agentMetrics.recordBoundaryViolation(
                    "output_too_short", "fail", boundaries.outputMinChars, effectiveContent.length
                )
                logger.warn {
                    "Output too short: ${effectiveContent.length} chars " +
                        "(min: ${boundaries.outputMinChars}), failing (FAIL mode)"
                }
                null
            }
        }
    }

    /**
     * Make one additional LLM call requesting a longer response.
     * Follows the same pattern as [attemptRepair].
     */
    private suspend fun attemptLongerResponse(
        shortContent: String,
        minChars: Int,
        command: AgentCommand
    ): String? {
        return runSuspendCatchingNonCancellation {
            val retryPrompt = "Your previous response was too short " +
                "(${shortContent.length} chars, minimum $minChars chars). " +
                "Please provide a more detailed response."
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
                    checkGuard(command)?.let { rejection ->
                        agentMetrics.recordGuardRejection(
                            stage = rejection.stage ?: "unknown",
                            reason = rejection.reason
                        )
                        emit(StreamEventMarker.error(rejection.reason))
                        return@withTimeout
                    }

                    // 2. Before hooks
                    checkBeforeHooks(hookContext)?.let { rejection ->
                        emit(StreamEventMarker.error(rejection.reason))
                        return@withTimeout
                    }

                    // 3. Intent resolution (after guard/hooks)
                    val effectiveCommand = resolveIntent(command)

                    // Reject structured formats in streaming mode
                    if (effectiveCommand.responseFormat != ResponseFormat.TEXT) {
                        emit(StreamEventMarker.error("Structured ${effectiveCommand.responseFormat} output is not supported in streaming mode"))
                        return@withTimeout
                    }

                    streamStarted = true

                    // 4. Setup (conversation via ConversationManager)
                    val conversationHistory = conversationManager.loadHistory(effectiveCommand)
                    val ragContext = retrieveRagContext(effectiveCommand)
                    val systemPrompt = systemPromptBuilder.build(
                        effectiveCommand.systemPrompt, ragContext,
                        effectiveCommand.responseFormat, effectiveCommand.responseSchema
                    )
                    var activeTools = if (effectiveCommand.mode == AgentMode.STANDARD) {
                        emptyList()
                    } else {
                        selectAndPrepareTools(effectiveCommand.userPrompt)
                    }
                    var chatOptions = buildChatOptions(effectiveCommand, activeTools.isNotEmpty())

                    logger.debug { "Streaming ReAct: ${activeTools.size} tools selected (mode=${effectiveCommand.mode})" }

                    // 5. Build message list for ReAct loop
                    val activeChatClient = resolveChatClient(effectiveCommand)
                    val messages = mutableListOf<Message>()
                    if (conversationHistory.isNotEmpty()) {
                        messages.addAll(conversationHistory)
                    }
                    messages.add(MediaConverter.buildUserMessage(effectiveCommand.userPrompt, effectiveCommand.media))

                    val maxToolCallLimit = minOf(effectiveCommand.maxToolCalls, properties.maxToolCalls).coerceAtLeast(1)
                    var totalToolCalls = 0
                    val allowedTools = resolveAllowedTools(effectiveCommand)

                    // 6. Streaming ReAct Loop: Stream → Detect Tool Calls → Execute → Re-Stream
                    while (true) {
                        // Reset last iteration content for memory persistence (only final iteration is saved)
                        lastIterationContent = StringBuilder()

                        // Trim messages to fit context window before each LLM call
                        messageTrimmer.trim(messages, systemPrompt)

                        val requestSpec = buildRequestSpec(
                            activeChatClient, systemPrompt, messages, chatOptions, activeTools
                        )
                        val flux = callWithRetry { requestSpec.stream().chatResponse() }
                        var pendingToolCalls: List<AssistantMessage.ToolCall> = emptyList()
                        val currentChunkText = StringBuilder()

                        // Emit text in real-time, detect tool calls from chunks
                        flux.asFlow().collect { chunk ->
                            val text = chunk.result.output.text
                            if (!text.isNullOrEmpty()) {
                                emit(text)
                                currentChunkText.append(text)
                                collectedContent.append(text)
                                lastIterationContent.append(text)
                            }

                            // Capture tool calls (typically present on the final chunk)
                            val chunkToolCalls = chunk.result.output.toolCalls
                            if (!chunkToolCalls.isNullOrEmpty()) {
                                pendingToolCalls = chunkToolCalls
                            }
                        }

                        // No tool calls or no tools → streaming complete
                        if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
                            streamSuccess = true
                            break
                        }

                        // Add assistant message (with tool calls) to conversation
                        val assistantMsg = AssistantMessage.builder()
                            .content(currentChunkText.toString())
                            .toolCalls(pendingToolCalls)
                            .build()
                        messages.add(assistantMsg)

                        // Emit tool start markers for frontend
                        for (toolCall in pendingToolCalls) {
                            emit(StreamEventMarker.toolStart(toolCall.name()))
                        }

                        // Execute tool calls in parallel
                        val totalToolCallsCounter = AtomicInteger(totalToolCalls)
                        val toolResponses = toolCallOrchestrator.executeInParallel(
                            pendingToolCalls, activeTools, hookContext, toolsUsed,
                            totalToolCallsCounter, maxToolCallLimit, allowedTools
                        )
                        totalToolCalls = totalToolCallsCounter.get()

                        // Emit tool end markers for frontend
                        for (toolCall in pendingToolCalls) {
                            emit(StreamEventMarker.toolEnd(toolCall.name()))
                        }

                        // Add tool results to conversation and loop back to LLM
                        messages.add(
                            ToolResponseMessage.builder()
                                .responses(toolResponses)
                                .build()
                        )

                        // Safety: if maxToolCalls limit reached, remove tools so LLM produces final text answer
                        if (totalToolCalls >= maxToolCallLimit) {
                            logger.info {
                                "maxToolCalls reached in streaming " +
                                    "($totalToolCalls/$maxToolCallLimit), final answer"
                            }
                            activeTools = emptyList()
                            chatOptions = buildChatOptions(effectiveCommand, false)
                        }
                    }

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
            val errorCode = classifyError(e)
            streamErrorCode = errorCode
            streamErrorMessage = errorMessageResolver.resolve(errorCode, e.message)
            emit(StreamEventMarker.error(streamErrorMessage.orEmpty()))
        } finally {
            // Save conversation history via ConversationManager (only on success, outside withTimeout)
            if (streamSuccess) {
                conversationManager.saveStreamingHistory(command, lastIterationContent.toString())
            }

            // Streaming boundary check (post-stream, informational only).
            // emit() is wrapped in try-catch because the collector may have already
            // cancelled (e.g., client disconnect), and an uncaught CancellationException
            // here would skip subsequent hook/metrics cleanup.
            if (streamSuccess) {
                val boundaries = properties.boundaries
                val contentLength = collectedContent.length
                if (boundaries.outputMaxChars > 0 && contentLength > boundaries.outputMaxChars) {
                    agentMetrics.recordBoundaryViolation(
                        "output_too_long", "warn", boundaries.outputMaxChars, contentLength
                    )
                    logger.warn {
                        "Streaming output exceeded max: $contentLength chars " +
                            "(max: ${boundaries.outputMaxChars})"
                    }
                    try {
                        emit(StreamEventMarker.error(
                            "Output too long ($contentLength chars, max: ${boundaries.outputMaxChars})"
                        ))
                    } catch (_: Exception) {
                        logger.debug { "Could not emit boundary error (collector cancelled)" }
                    }
                }
                if (boundaries.outputMinChars > 0 && contentLength < boundaries.outputMinChars) {
                    val policy = when (boundaries.outputMinViolationMode) {
                        OutputMinViolationMode.RETRY_ONCE -> "warn" // falls back to warn in streaming
                        else -> boundaries.outputMinViolationMode.name.lowercase()
                    }
                    agentMetrics.recordBoundaryViolation(
                        "output_too_short", policy, boundaries.outputMinChars, contentLength
                    )
                    logger.warn {
                        "Streaming output too short: $contentLength chars " +
                            "(min: ${boundaries.outputMinChars}, policy: $policy)"
                    }
                    try {
                        emit(StreamEventMarker.error(
                            "Output too short ($contentLength chars, min: ${boundaries.outputMinChars})"
                        ))
                    } catch (_: Exception) {
                        logger.debug { "Could not emit boundary error (collector cancelled)" }
                    }
                }
            }

            // AfterAgentComplete hook (only if stream passed guard/hook checks)
            if (streamStarted) {
                try {
                    hookExecutor?.executeAfterAgentComplete(
                        context = hookContext,
                        response = AgentResponse(
                            success = streamSuccess,
                            response = if (streamSuccess) collectedContent.toString() else null,
                            errorMessage = if (!streamSuccess) (streamErrorMessage ?: "Streaming failed") else null,
                            toolsUsed = toolsUsed.toList()
                        )
                    )
                } catch (hookEx: Exception) {
                    logger.error(hookEx) { "AfterAgentComplete hook failed in streaming finally" }
                }
            }

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

    /**
     * Retrieve RAG context if enabled and pipeline is available.
     */
    private suspend fun retrieveRagContext(command: AgentCommand): String? {
        if (!properties.rag.enabled || ragPipeline == null) return null

        return runSuspendCatchingNonCancellation {
            val ragFilters = extractRagFilters(command.metadata)
            val ragResult = ragPipeline.retrieve(
                RagQuery(
                    query = command.userPrompt,
                    filters = ragFilters,
                    topK = properties.rag.topK,
                    rerank = properties.rag.rerankEnabled
                )
            )
            if (ragResult.hasDocuments) ragResult.context else null
        }.getOrElse { e ->
            logger.warn(e) { "RAG retrieval failed, continuing without context" }
            null
        }
    }

    private fun extractRagFilters(metadata: Map<String, Any>): Map<String, Any> {
        if (metadata.isEmpty()) return emptyMap()

        val merged = linkedMapOf<String, Any>()

        val explicit = metadata["ragFilters"] as? Map<*, *>
        explicit?.forEach { (k, v) ->
            val key = k?.toString()?.trim().orEmpty()
            if (key.isNotBlank() && v != null) {
                merged[key] = v
            }
        }

        metadata.forEach { (k, v) ->
            if (!k.startsWith("rag.filter.")) return@forEach
            val key = k.removePrefix("rag.filter.").trim()
            if (key.isNotBlank() && key !in merged) {
                merged[key] = v
            }
        }

        return merged
    }

    /**
     * Validate structured output and attempt one repair if invalid.
     * Returns the validated content or an INVALID_RESPONSE failure.
     */
    private suspend fun validateAndRepairResponse(
        rawContent: String,
        format: ResponseFormat,
        command: AgentCommand,
        tokenUsage: TokenUsage?,
        toolsUsed: List<String>
    ): AgentResult {
        if (format == ResponseFormat.TEXT) {
            return AgentResult.success(content = rawContent, toolsUsed = toolsUsed, tokenUsage = tokenUsage)
        }

        val stripped = structuredOutputValidator.stripMarkdownCodeFence(rawContent)

        if (structuredOutputValidator.isValidFormat(stripped, format)) {
            return AgentResult.success(content = stripped, toolsUsed = toolsUsed, tokenUsage = tokenUsage)
        }

        // Attempt one LLM repair call
        logger.warn { "Invalid $format response detected, attempting repair" }
        val repaired = attemptRepair(stripped, format, command)
        if (repaired != null && structuredOutputValidator.isValidFormat(repaired, format)) {
            return AgentResult.success(content = repaired, toolsUsed = toolsUsed, tokenUsage = tokenUsage)
        }

        logger.error { "Structured output validation failed after repair attempt" }
        return AgentResult.failure(
            errorMessage = errorMessageResolver.resolve(AgentErrorCode.INVALID_RESPONSE, null),
            errorCode = AgentErrorCode.INVALID_RESPONSE
        )
    }

    private suspend fun attemptRepair(
        invalidContent: String,
        format: ResponseFormat,
        command: AgentCommand
    ): String? {
        return runSuspendCatchingNonCancellation {
            val formatName = format.name
            val repairPrompt = "The following $formatName is invalid. " +
                "Fix it and return ONLY valid $formatName with no explanation or code fences:\n\n$invalidContent"

            val activeChatClient = resolveChatClient(command)
            val response = kotlinx.coroutines.runInterruptible {
                activeChatClient
                    .prompt()
                    .user(repairPrompt)
                    .call()
                    .chatResponse()
            }
            val repairedContent = response?.results?.firstOrNull()?.output?.text
            if (repairedContent != null) structuredOutputValidator.stripMarkdownCodeFence(repairedContent) else null
        }.getOrElse { e ->
            logger.warn(e) { "Repair attempt failed" }
            null
        }
    }

    /**
     * Select and prepare tools using ToolSelector.
     *
     * Flow:
     * 1. Collect all ToolCallback instances (custom + MCP)
     * 2. Filter via ToolSelector (if available)
     * 3. Wrap as Spring AI ToolCallbacks
     * 4. Combine with LocalTool instances (@Tool annotation)
     * 5. Apply maxToolsPerRequest limit
     */
    private fun selectAndPrepareTools(userPrompt: String): List<Any> {
        // 1. LocalTool instances (Spring AI handles @Tool annotations directly)
        val localToolInstances = localTools.toList()

        // 2. Collect all ToolCallback instances (custom + MCP)
        val allCallbacks = toolCallbacks + mcpToolCallbacks()

        // 3. Apply ToolSelector filtering
        val selectedCallbacks = if (toolSelector != null && allCallbacks.isNotEmpty()) {
            toolSelector.select(userPrompt, allCallbacks)
        } else {
            allCallbacks
        }

        // 4. Wrap as Spring AI ToolCallbacks
        val wrappedCallbacks = selectedCallbacks.map { ArcToolCallbackAdapter(it) }

        // 5. Combine and apply limit
        return (localToolInstances + wrappedCallbacks).take(properties.maxToolsPerRequest)
    }

    /**
     * Build ChatOptions from command and properties.
     */
    private fun buildChatOptions(command: AgentCommand, hasTools: Boolean): ChatOptions {
        val temperature = command.temperature ?: properties.llm.temperature
        val maxTokens = properties.llm.maxOutputTokens
        val provider = command.model ?: chatModelProvider?.defaultProvider() ?: properties.llm.defaultProvider
        val isGemini = provider.equals("gemini", ignoreCase = true) || provider.equals("vertex", ignoreCase = true)

        if (isGemini) {
            return GoogleGenAiChatOptions.builder()
                .temperature(temperature)
                .maxOutputTokens(maxTokens)
                .googleSearchRetrieval(true)
                .internalToolExecutionEnabled(!hasTools)
                .build()
        }

        return if (hasTools) {
            ToolCallingChatOptions.builder()
                .temperature(temperature)
                .maxTokens(maxTokens)
                .internalToolExecutionEnabled(false) // We manage the tool loop ourselves
                .build()
        } else {
            ChatOptions.builder()
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build()
        }
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

    private fun buildRequestSpec(
        activeChatClient: ChatClient,
        systemPrompt: String,
        messages: List<Message>,
        chatOptions: ChatOptions,
        tools: List<Any>
    ): ChatClient.ChatClientRequestSpec {
        var spec = activeChatClient.prompt()
        if (systemPrompt.isNotBlank()) spec = spec.system(systemPrompt)
        spec = spec.messages(messages)
        spec = spec.options(chatOptions)
        if (tools.isNotEmpty()) {
            // Separate @Tool annotated objects from ToolCallback implementations.
            // Spring AI's .tools() uses MethodToolCallbackProvider which expects @Tool annotations.
            // ToolCallback impls (e.g. ArcToolCallbackAdapter) must go to .toolCallbacks().
            val (callbacks, annotatedTools) = tools.partition {
                it is org.springframework.ai.tool.ToolCallback
            }
            if (annotatedTools.isNotEmpty()) {
                spec = spec.tools(*annotatedTools.toTypedArray())
            }
            if (callbacks.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                spec = spec.toolCallbacks(callbacks as List<org.springframework.ai.tool.ToolCallback>)
            }
        }
        return spec
    }

    private fun resolveAllowedTools(command: AgentCommand): Set<String>? {
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
            var totalToolCalls = 0
            val allowedTools = resolveAllowedTools(command)

            // System prompt (with RAG context and response format)
            val systemPrompt = systemPromptBuilder.build(
                command.systemPrompt, ragContext,
                command.responseFormat, command.responseSchema
            )

            // Build initial messages
            val messages = mutableListOf<Message>()
            if (conversationHistory.isNotEmpty()) {
                messages.addAll(conversationHistory)
            }
            messages.add(MediaConverter.buildUserMessage(command.userPrompt, command.media))

            var activeTools = tools
            var chatOptions = buildChatOptions(command, activeTools.isNotEmpty())
            var totalTokenUsage: TokenUsage? = null
            val activeChatClient = resolveChatClient(command)

            // ReAct loop: call LLM → execute tools → repeat until done or maxToolCalls
            while (true) {
                // Trim messages to fit context window before each LLM call
                messageTrimmer.trim(messages, systemPrompt)

                val requestSpec = buildRequestSpec(activeChatClient, systemPrompt, messages, chatOptions, activeTools)
                val chatResponse = callWithRetry {
                    kotlinx.coroutines.runInterruptible { requestSpec.call().chatResponse() }
                }

                totalTokenUsage = accumulateTokenUsage(chatResponse, totalTokenUsage)
                chatResponse?.metadata?.usage?.let { usage ->
                    agentMetrics.recordTokenUsage(TokenUsage(
                        promptTokens = usage.promptTokens.toInt(),
                        completionTokens = usage.completionTokens.toInt(),
                        totalTokens = usage.totalTokens.toInt()
                    ))
                }

                // Check for tool calls in the response
                val assistantOutput = chatResponse?.results?.firstOrNull()?.output
                val pendingToolCalls = assistantOutput?.toolCalls.orEmpty()

                if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
                    // No tool calls — validate structured output and return
                    return validateAndRepairResponse(
                        rawContent = assistantOutput?.text.orEmpty(),
                        format = command.responseFormat,
                        command = command,
                        tokenUsage = totalTokenUsage,
                        toolsUsed = ArrayList(toolsUsed)
                    )
                }

                // Add assistant message (with tool calls) to conversation
                messages.add(assistantOutput!!)

                // Execute tool calls in parallel
                val totalToolCallsCounter = AtomicInteger(totalToolCalls)
                val toolResponses = toolCallOrchestrator.executeInParallel(
                    pendingToolCalls, activeTools, hookContext, toolsUsed,
                    totalToolCallsCounter, maxToolCalls, allowedTools
                )
                totalToolCalls = totalToolCallsCounter.get()

                // Add tool results to conversation and loop back to LLM
                messages.add(
                    ToolResponseMessage.builder()
                        .responses(toolResponses)
                        .build()
                )

                // Safety: if maxToolCalls limit reached, remove tools so LLM produces final text answer
                if (totalToolCalls >= maxToolCalls) {
                    logger.info {
                        "maxToolCalls reached ($totalToolCalls/$maxToolCalls), " +
                            "final answer"
                    }
                    activeTools = emptyList()
                    chatOptions = buildChatOptions(command, false)
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "LLM call with tools failed" }
            val errorCode = classifyError(e)
            return AgentResult.failure(
                errorMessage = errorMessageResolver.resolve(errorCode, e.message),
                errorCode = errorCode
            )
        }
    }

    /**
     * Call a block with circuit breaker protection and retry with exponential backoff.
     *
     * If a [CircuitBreaker] is configured, the entire retry sequence is wrapped within it.
     * When the circuit is OPEN, calls fail immediately with [CircuitBreakerOpenException].
     */
    private suspend fun <T> callWithRetry(block: suspend () -> T): T {
        val retryBlock: suspend () -> T = {
            val retry = properties.retry
            val maxAttempts = retry.maxAttempts.coerceAtLeast(1)
            var lastException: Exception? = null
            var result: T? = null
            var completed = false

            repeat(maxAttempts) { attempt ->
                if (completed) return@repeat
                try {
                    result = block()
                    completed = true
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    lastException = e
                    if (!isTransientError(e) || attempt == maxAttempts - 1) {
                        throw e
                    }
                    val baseDelay = minOf(
                        (retry.initialDelayMs * Math.pow(retry.multiplier, attempt.toDouble())).toLong(),
                        retry.maxDelayMs
                    )
                    // Add ±25% jitter to prevent thundering herd
                    val jitter = (baseDelay * 0.25 * (Math.random() * 2 - 1)).toLong()
                    val delayMs = (baseDelay + jitter).coerceAtLeast(0)
                    logger.warn {
                        "Transient error (attempt ${attempt + 1}/$maxAttempts), " +
                            "retrying in ${delayMs}ms: ${e.message}"
                    }
                    delay(delayMs)
                }
            }

            if (completed) {
                @Suppress("UNCHECKED_CAST")
                result as T
            } else {
                throw lastException ?: IllegalStateException("Retry exhausted")
            }
        }

        return if (circuitBreaker != null) {
            circuitBreaker.execute(retryBlock)
        } else {
            retryBlock()
        }
    }

    private fun accumulateTokenUsage(
        chatResponse: org.springframework.ai.chat.model.ChatResponse?,
        previous: TokenUsage?
    ): TokenUsage? {
        val usage = chatResponse?.metadata?.usage ?: return previous
        val current = TokenUsage(
            promptTokens = usage.promptTokens.toInt(),
            completionTokens = usage.completionTokens.toInt(),
            totalTokens = usage.totalTokens.toInt()
        )
        return previous?.let {
            TokenUsage(
                promptTokens = it.promptTokens + current.promptTokens,
                completionTokens = it.completionTokens + current.completionTokens,
                totalTokens = it.totalTokens + current.totalTokens
            )
        } ?: current
    }

    private fun isTransientError(e: Exception): Boolean = transientErrorClassifier(e)

    private fun classifyError(e: Exception): AgentErrorCode {
        return when {
            e is CircuitBreakerOpenException -> AgentErrorCode.CIRCUIT_BREAKER_OPEN
            e.message?.contains("rate limit", ignoreCase = true) == true -> AgentErrorCode.RATE_LIMITED
            e.message?.contains("timeout", ignoreCase = true) == true -> AgentErrorCode.TIMEOUT
            e.message?.contains("context length", ignoreCase = true) == true -> AgentErrorCode.CONTEXT_TOO_LONG
            e.message?.contains("tool", ignoreCase = true) == true -> AgentErrorCode.TOOL_ERROR
            else -> AgentErrorCode.UNKNOWN
        }
    }

}

/**
 * Adapter that wraps Arc Reactor's ToolCallback as a Spring AI ToolCallback.
 *
 * Bridges the framework-agnostic ToolCallback interface to Spring AI's
 * tool calling system, enabling integration with ChatClient.tools().
 */
internal class ArcToolCallbackAdapter(
    val arcCallback: ToolCallback
) : org.springframework.ai.tool.ToolCallback {

    private val toolDefinition = ToolDefinition.builder()
        .name(arcCallback.name)
        .description(arcCallback.description)
        .inputSchema(arcCallback.inputSchema)
        .build()

    override fun getToolDefinition(): ToolDefinition = toolDefinition

    override fun getToolMetadata(): ToolMetadata = ToolMetadata.builder().build()

    override fun call(toolInput: String): String {
        val args = parseToolArguments(toolInput)
        // Bridge suspend call to blocking for Spring AI compatibility (runs on IO dispatcher)
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            arcCallback.call(args)?.toString() ?: ""
        }
    }
}

/**
 * Exception thrown when a classified intent is in the blocked list.
 */
class BlockedIntentException(
    val intentName: String
) : Exception("Intent '$intentName' is blocked by policy")
