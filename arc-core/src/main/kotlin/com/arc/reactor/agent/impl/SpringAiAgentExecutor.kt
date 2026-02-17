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
import com.arc.reactor.resilience.CircuitBreakerOpenException
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.response.ResponseFilterChain
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.agent.model.TokenUsage
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
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.UUID

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
    private val retryExecutor = RetryExecutor(
        retry = properties.retry,
        circuitBreaker = circuitBreaker,
        isTransientError = ::isTransientError
    )
    private val manualReActLoopExecutor = ManualReActLoopExecutor(
        messageTrimmer = messageTrimmer,
        toolCallOrchestrator = toolCallOrchestrator,
        buildRequestSpec = ::buildRequestSpec,
        callWithRetry = { block -> retryExecutor.execute(block) },
        buildChatOptions = ::buildChatOptions,
        validateAndRepairResponse = ::validateAndRepairResponse,
        recordTokenUsage = agentMetrics::recordTokenUsage
    )
    private val streamingReActLoopExecutor = StreamingReActLoopExecutor(
        messageTrimmer = messageTrimmer,
        toolCallOrchestrator = toolCallOrchestrator,
        buildRequestSpec = ::buildRequestSpec,
        callWithRetry = { block -> retryExecutor.execute(block) },
        buildChatOptions = ::buildChatOptions
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
        selectAndPrepareTools = ::selectAndPrepareTools,
        retrieveRagContext = ::retrieveRagContext,
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
            hookEx.throwIfCancellation()
            logger.error(hookEx) { "AfterAgentComplete hook failed during error handling" }
        }
        agentMetrics.recordExecution(failResult)
        return failResult
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
                    val ragContext = retrieveRagContext(effectiveCommand)
                    val systemPrompt = systemPromptBuilder.build(
                        effectiveCommand.systemPrompt, ragContext,
                        effectiveCommand.responseFormat, effectiveCommand.responseSchema
                    )
                    val selectedTools = if (effectiveCommand.mode == AgentMode.STANDARD) {
                        emptyList()
                    } else {
                        selectAndPrepareTools(effectiveCommand.userPrompt)
                    }

                    logger.debug { "Streaming ReAct: ${selectedTools.size} tools selected (mode=${effectiveCommand.mode})" }

                    // 5. Run streaming ReAct loop
                    val activeChatClient = resolveChatClient(effectiveCommand)
                    val maxToolCallLimit = minOf(effectiveCommand.maxToolCalls, properties.maxToolCalls).coerceAtLeast(1)
                    val allowedTools = resolveAllowedTools(effectiveCommand)

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
            val errorCode = classifyError(e)
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
                .googleSearchRetrieval(properties.llm.googleSearchRetrievalEnabled)
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
            val allowedTools = resolveAllowedTools(command)
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
            val errorCode = classifyError(e)
            return AgentResult.failure(
                errorMessage = errorMessageResolver.resolve(errorCode, e.message),
                errorCode = errorCode
            )
        }
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
