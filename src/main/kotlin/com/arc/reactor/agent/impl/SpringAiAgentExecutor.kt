package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.memory.DefaultConversationManager
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import org.slf4j.MDC
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}
private val objectMapper = jacksonObjectMapper()
private val mapTypeRef = object : TypeReference<Map<String, Any?>>() {}
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

private fun parseJsonToMap(json: String?): Map<String, Any?> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        objectMapper.readValue(json, mapTypeRef)
    } catch (e: Exception) {
        logger.warn(e) { "Failed to parse JSON arguments" }
        emptyMap()
    }
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
    private val conversationManager: ConversationManager = DefaultConversationManager(memoryStore, properties)
) : AgentExecutor {

    init {
        val llm = properties.llm
        require(llm.maxContextWindowTokens > llm.maxOutputTokens) {
            "maxContextWindowTokens (${llm.maxContextWindowTokens}) " +
                "must be greater than maxOutputTokens (${llm.maxOutputTokens})"
        }
    }

    private val concurrencySemaphore = Semaphore(properties.concurrency.maxConcurrentRequests)

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
            toolsUsed = toolsUsed
        )

        try {
            return concurrencySemaphore.withPermit {
                withTimeout(properties.concurrency.requestTimeoutMs) {
                    executeInternal(command, hookContext, toolsUsed, startTime)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "Request timed out after ${properties.concurrency.requestTimeoutMs}ms" }
            return handleFailureWithHook(AgentErrorCode.TIMEOUT, e, hookContext, startTime)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // Rethrow to support structured concurrency
        } catch (e: Exception) {
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
        val result = guard.guard(GuardCommand(userId = userId, text = command.userPrompt))
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

        val conversationHistory = conversationManager.loadHistory(command)
        val ragContext = retrieveRagContext(command.userPrompt)
        val selectedTools = if (command.mode == AgentMode.STANDARD) {
            emptyList()
        } else {
            selectAndPrepareTools(command.userPrompt)
        }
        logger.debug { "Selected ${selectedTools.size} tools for execution (mode=${command.mode})" }

        val result = executeWithTools(
            command = command, tools = selectedTools,
            conversationHistory = conversationHistory,
            hookContext = hookContext, toolsUsed = toolsUsed, ragContext = ragContext
        )

        return finishExecution(result, command, hookContext, toolsUsed, startTime)
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

    private suspend fun finishExecution(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        startTime: Long
    ): AgentResult {
        conversationManager.saveHistory(command, result)
        try {
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(
                    success = result.success, response = result.content,
                    errorMessage = result.errorMessage, toolsUsed = toolsUsed
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "AfterAgentComplete hook failed" }
        }
        val finalResult = result.copy(durationMs = System.currentTimeMillis() - startTime)
        agentMetrics.recordExecution(finalResult)
        return finalResult
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
            toolsUsed = toolsUsed
        )

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
                        emit(rejection.reason)
                        return@withTimeout
                    }

                    // 2. Before hooks
                    checkBeforeHooks(hookContext)?.let { rejection ->
                        emit(rejection.reason)
                        return@withTimeout
                    }

                    // Reject JSON format in streaming mode
                    if (command.responseFormat == ResponseFormat.JSON) {
                        emit("[error] Structured JSON output is not supported in streaming mode")
                        return@withTimeout
                    }

                    streamStarted = true

                    // 3. Setup (conversation via ConversationManager)
                    val conversationHistory = conversationManager.loadHistory(command)
                    val ragContext = retrieveRagContext(command.userPrompt)
                    val systemPrompt = buildSystemPrompt(command.systemPrompt, ragContext)
                    var activeTools = if (command.mode == AgentMode.STANDARD) {
                        emptyList()
                    } else {
                        selectAndPrepareTools(command.userPrompt)
                    }
                    var chatOptions = buildChatOptions(command, activeTools.isNotEmpty())

                    logger.debug { "Streaming ReAct: ${activeTools.size} tools selected (mode=${command.mode})" }

                    // 4. Build message list for ReAct loop
                    val activeChatClient = resolveChatClient(command)
                    val messages = mutableListOf<Message>()
                    if (conversationHistory.isNotEmpty()) {
                        messages.addAll(conversationHistory)
                    }
                    messages.add(UserMessage(command.userPrompt))

                    val maxToolCallLimit = minOf(command.maxToolCalls, properties.maxToolCalls).coerceAtLeast(1)
                    var totalToolCalls = 0

                    // 5. Streaming ReAct Loop: Stream → Detect Tool Calls → Execute → Re-Stream
                    while (true) {
                        // Reset last iteration content for memory persistence (only final iteration is saved)
                        lastIterationContent = StringBuilder()

                        // Trim messages to fit context window before each LLM call
                        trimMessagesToFitContext(messages, systemPrompt)

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
                        val toolResponses = executeToolCallsInParallel(
                            pendingToolCalls, activeTools, hookContext, toolsUsed,
                            totalToolCallsCounter, maxToolCallLimit
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
                            chatOptions = buildChatOptions(command, false)
                        }
                    }

                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "Streaming request timed out after ${properties.concurrency.requestTimeoutMs}ms" }
            streamErrorCode = AgentErrorCode.TIMEOUT
            streamErrorMessage = errorMessageResolver.resolve(AgentErrorCode.TIMEOUT, e.message)
            emit("[error] $streamErrorMessage")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // Rethrow to support structured concurrency
        } catch (e: Exception) {
            logger.error(e) { "Streaming execution failed" }
            val errorCode = classifyError(e)
            streamErrorCode = errorCode
            streamErrorMessage = errorMessageResolver.resolve(errorCode, e.message)
            emit("[error] $streamErrorMessage")
        } finally {
            // Save conversation history via ConversationManager (only on success, outside withTimeout)
            if (streamSuccess) {
                conversationManager.saveStreamingHistory(command, lastIterationContent.toString())
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
        agentMetrics.recordExecution(result)
    }

    /**
     * Retrieve RAG context if enabled and pipeline is available.
     */
    private suspend fun retrieveRagContext(userPrompt: String): String? {
        if (!properties.rag.enabled || ragPipeline == null) return null

        return try {
            val ragResult = ragPipeline.retrieve(
                RagQuery(
                    query = userPrompt,
                    topK = properties.rag.topK,
                    rerank = properties.rag.rerankEnabled
                )
            )
            if (ragResult.hasDocuments) ragResult.context else null
        } catch (e: Exception) {
            logger.warn(e) { "RAG retrieval failed, continuing without context" }
            null
        }
    }

    /**
     * Build system prompt with optional RAG context and response format instructions.
     */
    private fun buildSystemPrompt(
        basePrompt: String,
        ragContext: String?,
        responseFormat: ResponseFormat = ResponseFormat.TEXT,
        responseSchema: String? = null
    ): String {
        val parts = mutableListOf(basePrompt)

        if (ragContext != null) {
            parts.add("[Retrieved Context]\n$ragContext")
        }

        if (responseFormat == ResponseFormat.JSON) {
            val jsonInstruction = buildString {
                append("[Response Format]\n")
                append("You MUST respond with valid JSON only. Do not include any text outside the JSON object.")
                if (responseSchema != null) {
                    append("\n\nExpected JSON schema:\n$responseSchema")
                }
            }
            parts.add(jsonInstruction)
        }

        return parts.joinToString("\n\n")
    }

    /**
     * Trim messages to fit within the context window token budget.
     *
     * Budget = maxContextWindowTokens - systemPromptTokens - maxOutputTokens.
     * Removes oldest message groups first. A "group" is:
     * - A standalone UserMessage or AssistantMessage (no tool calls)
     * - An [AssistantMessage(toolCalls) + ToolResponseMessage] pair (must stay together)
     *
     * This prevents orphaned ToolResponseMessages which would cause LLM API errors.
     */
    private fun trimMessagesToFitContext(messages: MutableList<Message>, systemPrompt: String) {
        val maxTokens = properties.llm.maxContextWindowTokens
        val systemTokens = tokenEstimator.estimate(systemPrompt)
        val outputReserve = properties.llm.maxOutputTokens
        val budget = maxTokens - systemTokens - outputReserve

        if (budget <= 0) {
            logger.warn {
                "Context budget is non-positive ($budget). " +
                    "system=$systemTokens, outputReserve=$outputReserve, max=$maxTokens"
            }
            val lastUserMsgIndex = messages.indexOfLast { it is UserMessage }
            if (lastUserMsgIndex >= 0 && messages.size > 1) {
                val userMsg = messages[lastUserMsgIndex]
                messages.clear()
                messages.add(userMsg)
            }
            return
        }

        var totalTokens = messages.sumOf { estimateMessageTokens(it) }
        totalTokens = trimOldHistory(messages, totalTokens, budget)
        trimToolHistory(messages, totalTokens, budget)
    }

    /** Phase 1: Remove oldest messages from the front, preserving the last UserMessage. */
    private fun trimOldHistory(messages: MutableList<Message>, currentTokens: Int, budget: Int): Int {
        var totalTokens = currentTokens
        while (totalTokens > budget && messages.size > 1) {
            val protectedIdx = messages.indexOfLast { it is UserMessage }.coerceAtLeast(0)
            if (protectedIdx <= 0) break

            val removeCount = calculateRemoveGroupSize(messages)
            if (removeCount <= 0 || removeCount > protectedIdx) break

            var removedTokens = 0
            repeat(removeCount) {
                if (messages.size > 1) {
                    removedTokens += estimateMessageTokens(messages.removeFirst())
                }
            }
            totalTokens -= removedTokens
            logger.debug { "Trimmed $removeCount messages (old history). Remaining tokens: $totalTokens/$budget" }
        }
        return totalTokens
    }

    /** Phase 2: Remove tool interaction pairs after the last UserMessage when still over budget. */
    private fun trimToolHistory(messages: MutableList<Message>, currentTokens: Int, budget: Int) {
        var totalTokens = currentTokens
        while (totalTokens > budget && messages.size > 1) {
            val protectedIdx = messages.indexOfLast { it is UserMessage }.coerceAtLeast(0)
            val removeStartIdx = protectedIdx + 1
            if (removeStartIdx >= messages.size - 1) break

            val subList = messages.subList(removeStartIdx, messages.size)
            val removeCount = calculateRemoveGroupSize(subList)
            if (removeCount <= 0 || removeStartIdx + removeCount > messages.size) break

            var removedTokens = 0
            repeat(removeCount) {
                if (removeStartIdx < messages.size) {
                    removedTokens += estimateMessageTokens(messages.removeAt(removeStartIdx))
                }
            }
            totalTokens -= removedTokens
            logger.debug { "Trimmed $removeCount messages (tool history). Remaining tokens: $totalTokens/$budget" }
        }
    }

    /**
     * Calculate how many messages from the front should be removed as a group.
     *
     * If the first message is an AssistantMessage with tool calls, the following
     * ToolResponseMessage must also be removed to maintain valid message ordering.
     */
    private fun calculateRemoveGroupSize(messages: List<Message>): Int {
        if (messages.isEmpty()) return 0
        val first = messages[0]

        // AssistantMessage with tool calls → must also remove the paired ToolResponseMessage
        if (first is AssistantMessage && !first.toolCalls.isNullOrEmpty()) {
            // Find the ToolResponseMessage that follows
            return if (messages.size > 1 && messages[1] is ToolResponseMessage) 2 else 1
        }

        // ToolResponseMessage without preceding AssistantMessage (orphaned) → remove it
        if (first is ToolResponseMessage) return 1

        // Regular UserMessage or AssistantMessage → remove single
        return 1
    }

    private fun estimateMessageTokens(message: Message): Int {
        return when (message) {
            is UserMessage -> tokenEstimator.estimate(message.text)
            is AssistantMessage -> {
                val textTokens = tokenEstimator.estimate(message.text ?: "")
                val toolCallTokens = message.toolCalls.sumOf {
                    tokenEstimator.estimate(it.name() + it.arguments())
                }
                textTokens + toolCallTokens
            }
            is SystemMessage -> tokenEstimator.estimate(message.text)
            is ToolResponseMessage -> message.responses.sumOf { tokenEstimator.estimate(it.responseData()) }
            else -> tokenEstimator.estimate(message.text ?: "")
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

    /**
     * Find a tool adapter by name from the registered tools.
     */
    private fun findToolAdapter(toolName: String, tools: List<Any>): ArcToolCallbackAdapter? {
        return tools.filterIsInstance<ArcToolCallbackAdapter>().firstOrNull { it.arcCallback.name == toolName }
    }

    /**
     * Execute multiple tool calls in parallel using coroutines.
     * Results are returned in the same order as the input tool calls.
     */
    private suspend fun executeToolCallsInParallel(
        toolCalls: List<AssistantMessage.ToolCall>,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        totalToolCallsCounter: AtomicInteger,
        maxToolCalls: Int
    ): List<ToolResponseMessage.ToolResponse> = coroutineScope {
        toolCalls.map { toolCall ->
            async {
                executeSingleToolCall(toolCall, tools, hookContext, toolsUsed, totalToolCallsCounter, maxToolCalls)
            }
        }.awaitAll()
    }

    /**
     * Execute a single tool call with hooks and metrics.
     */
    private suspend fun executeSingleToolCall(
        toolCall: AssistantMessage.ToolCall,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        totalToolCallsCounter: AtomicInteger,
        maxToolCalls: Int
    ): ToolResponseMessage.ToolResponse {
        val currentCount = totalToolCallsCounter.getAndIncrement()
        if (currentCount >= maxToolCalls) {
            logger.warn { "maxToolCalls ($maxToolCalls) reached, stopping tool execution" }
            return ToolResponseMessage.ToolResponse(
                toolCall.id(), toolCall.name(),
                "Error: Maximum tool call limit ($maxToolCalls) reached"
            )
        }

        val toolName = toolCall.name()
        val toolCallContext = ToolCallContext(
            agentContext = hookContext, toolName = toolName,
            toolParams = parseJsonToMap(toolCall.arguments()), callIndex = currentCount
        )

        checkBeforeToolCallHook(toolCallContext)?.let { rejection ->
            logger.info { "Tool call $toolName rejected by hook: ${rejection.reason}" }
            return ToolResponseMessage.ToolResponse(
                toolCall.id(), toolName, "Tool call rejected: ${rejection.reason}"
            )
        }

        val toolStartTime = System.currentTimeMillis()
        val (toolOutput, toolSuccess) = invokeToolAdapter(toolName, toolCall, tools, toolsUsed)
        val toolDurationMs = System.currentTimeMillis() - toolStartTime

        hookExecutor?.executeAfterToolCall(
            context = toolCallContext,
            result = ToolCallResult(
                success = toolSuccess,
                output = toolOutput, durationMs = toolDurationMs
            )
        )

        agentMetrics.recordToolCall(toolName, toolDurationMs, toolSuccess)
        return ToolResponseMessage.ToolResponse(toolCall.id(), toolName, toolOutput)
    }

    private suspend fun checkBeforeToolCallHook(context: ToolCallContext): HookResult.Reject? {
        if (hookExecutor == null) return null
        return hookExecutor.executeBeforeToolCall(context) as? HookResult.Reject
    }

    private suspend fun invokeToolAdapter(
        toolName: String,
        toolCall: AssistantMessage.ToolCall,
        tools: List<Any>,
        toolsUsed: MutableList<String>
    ): Pair<String, Boolean> {
        val adapter = findToolAdapter(toolName, tools)
        return if (adapter != null) {
            toolsUsed.add(toolName)
            try {
                val output = withTimeout(properties.concurrency.toolCallTimeoutMs) {
                    adapter.call(toolCall.arguments())
                }
                Pair(output, true)
            } catch (e: TimeoutCancellationException) {
                val timeoutMs = properties.concurrency.toolCallTimeoutMs
                logger.error { "Tool $toolName timed out after ${timeoutMs}ms" }
                Pair("Error: Tool '$toolName' timed out after ${timeoutMs}ms", false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Tool $toolName execution failed" }
                Pair("Error: ${e.message}", false)
            }
        } else {
            logger.warn { "Tool '$toolName' not found (possibly hallucinated by LLM)" }
            Pair("Error: Tool '$toolName' not found", false)
        }
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

            // System prompt (with RAG context and response format)
            val systemPrompt = buildSystemPrompt(
                command.systemPrompt, ragContext,
                command.responseFormat, command.responseSchema
            )

            // Build initial messages
            val messages = mutableListOf<Message>()
            if (conversationHistory.isNotEmpty()) {
                messages.addAll(conversationHistory)
            }
            messages.add(UserMessage(command.userPrompt))

            var activeTools = tools
            var chatOptions = buildChatOptions(command, activeTools.isNotEmpty())
            var totalTokenUsage: TokenUsage? = null
            val activeChatClient = resolveChatClient(command)

            // ReAct loop: call LLM → execute tools → repeat until done or maxToolCalls
            while (true) {
                // Trim messages to fit context window before each LLM call
                trimMessagesToFitContext(messages, systemPrompt)

                val requestSpec = buildRequestSpec(activeChatClient, systemPrompt, messages, chatOptions, activeTools)
                val chatResponse = callWithRetry {
                    kotlinx.coroutines.runInterruptible { requestSpec.call().chatResponse() }
                }

                totalTokenUsage = accumulateTokenUsage(chatResponse, totalTokenUsage)

                // Check for tool calls in the response
                val assistantOutput = chatResponse?.results?.firstOrNull()?.output
                val pendingToolCalls = assistantOutput?.toolCalls.orEmpty()

                if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
                    // No tool calls — return final content
                    return AgentResult.success(
                        content = assistantOutput?.text.orEmpty(),
                        toolsUsed = ArrayList(toolsUsed),
                        tokenUsage = totalTokenUsage
                    )
                }

                // Add assistant message (with tool calls) to conversation
                messages.add(assistantOutput!!)

                // Execute tool calls in parallel
                val totalToolCallsCounter = AtomicInteger(totalToolCalls)
                val toolResponses = executeToolCallsInParallel(
                    pendingToolCalls, activeTools, hookContext, toolsUsed,
                    totalToolCallsCounter, maxToolCalls
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
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // Rethrow to support withTimeout and structured concurrency
        } catch (e: Exception) {
            logger.error(e) { "LLM call with tools failed" }
            val errorCode = classifyError(e)
            return AgentResult.failure(
                errorMessage = errorMessageResolver.resolve(errorCode, e.message),
                errorCode = errorCode
            )
        }
    }

    /**
     * Call a block with retry and exponential backoff for transient errors.
     */
    private suspend fun <T> callWithRetry(block: suspend () -> T): T {
        val retry = properties.retry
        val maxAttempts = retry.maxAttempts.coerceAtLeast(1)
        var lastException: Exception? = null

        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e // Never retry cancellation (respects withTimeout)
            } catch (e: Exception) {
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

        throw lastException ?: IllegalStateException("Retry exhausted")
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
        val args = parseJsonToMap(toolInput)
        // Bridge suspend call to blocking for Spring AI compatibility (runs on IO dispatcher)
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            arcCallback.call(args)?.toString() ?: ""
        }
    }
}
