package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.agent.model.MessageRole
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
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.model.RagQuery
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.UUID

private val logger = KotlinLogging.logger {}
private val objectMapper = jacksonObjectMapper()
private val mapTypeRef = object : TypeReference<Map<String, Any?>>() {}

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
    private val ragPipeline: RagPipeline? = null
) : AgentExecutor {

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
            val failResult = AgentResult.failure(
                errorMessage = errorMessageResolver.resolve(AgentErrorCode.TIMEOUT, e.message),
                durationMs = System.currentTimeMillis() - startTime
            )
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(success = false, errorMessage = failResult.errorMessage)
            )
            agentMetrics.recordExecution(failResult)
            return failResult
        } catch (e: Exception) {
            logger.error(e) { "Agent execution failed" }
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(success = false, errorMessage = e.message)
            )
            val failResult = AgentResult.failure(
                errorMessage = e.message ?: "Unknown error",
                durationMs = System.currentTimeMillis() - startTime
            )
            agentMetrics.recordExecution(failResult)
            return failResult
        } finally {
            MDC.remove("runId")
            MDC.remove("userId")
            MDC.remove("sessionId")
        }
    }

    /**
     * Run guard check. Returns rejection result if rejected, null if allowed.
     */
    private suspend fun checkGuard(command: AgentCommand): GuardResult.Rejected? {
        if (guard == null || command.userId == null) return null
        val result = guard.guard(GuardCommand(userId = command.userId, text = command.userPrompt))
        return result as? GuardResult.Rejected
    }

    /**
     * Run before-agent-start hooks. Returns rejection/pending result if blocked, null if continue.
     */
    private suspend fun checkBeforeHooks(hookContext: HookContext): HookResult? {
        if (hookExecutor == null) return null
        return when (val result = hookExecutor.executeBeforeAgentStart(hookContext)) {
            is HookResult.Reject, is HookResult.PendingApproval -> result
            else -> null
        }
    }

    private suspend fun executeInternal(
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        startTime: Long
    ): AgentResult {
        // 1. Guard check
        checkGuard(command)?.let { rejection ->
            agentMetrics.recordGuardRejection(
                stage = rejection.stage ?: "unknown",
                reason = rejection.reason
            )
            val failResult = AgentResult.failure(
                errorMessage = rejection.reason,
                durationMs = System.currentTimeMillis() - startTime
            )
            agentMetrics.recordExecution(failResult)
            return failResult
        }

        // 2. Before Agent Start Hook
        checkBeforeHooks(hookContext)?.let { hookResult ->
            val message = when (hookResult) {
                is HookResult.Reject -> hookResult.reason
                is HookResult.PendingApproval -> "Pending approval: ${hookResult.message}"
                else -> "Blocked by hook"
            }
            val failResult = AgentResult.failure(
                errorMessage = message,
                durationMs = System.currentTimeMillis() - startTime
            )
            agentMetrics.recordExecution(failResult)
            return failResult
        }

        // 3. Load conversation history
        val conversationHistory = loadConversationHistory(command)

        // 4. RAG: Retrieve context if enabled
        val ragContext = retrieveRagContext(command.userPrompt)

        // 5. Select and prepare tools (respecting AgentMode)
        val selectedTools = if (command.mode == AgentMode.STANDARD) {
            emptyList()
        } else {
            selectAndPrepareTools(command.userPrompt)
        }
        logger.debug { "Selected ${selectedTools.size} tools for execution (mode=${command.mode})" }

        // 6. Agent execution (Spring AI Function Calling)
        val result = executeWithTools(
            command = command,
            tools = selectedTools,
            conversationHistory = conversationHistory,
            hookContext = hookContext,
            toolsUsed = toolsUsed,
            ragContext = ragContext
        )

        // 7. Save conversation history
        saveConversationHistory(command, result)

        // 8. After Agent Complete Hook
        hookExecutor?.executeAfterAgentComplete(
            context = hookContext,
            response = AgentResponse(
                success = result.success,
                response = result.content,
                errorMessage = result.errorMessage,
                toolsUsed = toolsUsed
            )
        )

        val finalResult = result.copy(durationMs = System.currentTimeMillis() - startTime)
        agentMetrics.recordExecution(finalResult)
        return finalResult
    }

    override fun executeStream(command: AgentCommand): Flow<String> = flow {
        val runId = UUID.randomUUID().toString()
        val hookContext = HookContext(
            runId = runId,
            userId = command.userId ?: "anonymous",
            userPrompt = command.userPrompt,
            toolsUsed = mutableListOf()
        )

        // 1. Guard check
        checkGuard(command)?.let { rejection ->
            agentMetrics.recordGuardRejection(
                stage = rejection.stage ?: "unknown",
                reason = rejection.reason
            )
            emit(rejection.reason)
            return@flow
        }

        // 2. Before hook
        checkBeforeHooks(hookContext)?.let { hookResult ->
            val message = when (hookResult) {
                is HookResult.Reject -> hookResult.reason
                is HookResult.PendingApproval -> "Pending approval: ${hookResult.message}"
                else -> "Blocked by hook"
            }
            emit(message)
            return@flow
        }

        // 3. Build request
        var requestSpec = chatClient.prompt()

        // RAG context
        val ragContext = retrieveRagContext(command.userPrompt)
        val systemPrompt = buildSystemPrompt(command.systemPrompt, ragContext)
        if (systemPrompt.isNotBlank()) {
            requestSpec = requestSpec.system(systemPrompt)
        }

        // Conversation history
        val conversationHistory = loadConversationHistory(command)
        if (conversationHistory.isNotEmpty()) {
            requestSpec = requestSpec.messages(conversationHistory)
        }

        requestSpec = requestSpec.user(command.userPrompt)

        // Tools (same as non-streaming, for models that support tool use in streaming)
        if (command.mode != AgentMode.STANDARD) {
            val selectedTools = selectAndPrepareTools(command.userPrompt)
            if (selectedTools.isNotEmpty()) {
                requestSpec = requestSpec.tools(*selectedTools.toTypedArray())
            }
        }

        // 4. Stream LLM response
        try {
            val flux = requestSpec.stream().content()
            val reactiveFlow = flux.asFlow()
            emitAll(reactiveFlow)
        } catch (e: Exception) {
            logger.error(e) { "Streaming execution failed" }
            emit("[error] ${translateError(e)}")
        }
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
     * Build system prompt with optional RAG context.
     */
    private fun buildSystemPrompt(basePrompt: String, ragContext: String?): String {
        return if (ragContext != null) {
            "$basePrompt\n\n[Retrieved Context]\n$ragContext"
        } else {
            basePrompt
        }
    }

    /**
     * Convert MessageRole to Spring AI Message.
     */
    private fun toSpringAiMessage(msg: com.arc.reactor.agent.model.Message): Message {
        return when (msg.role) {
            MessageRole.USER -> UserMessage(msg.content)
            MessageRole.ASSISTANT -> AssistantMessage(msg.content)
            MessageRole.SYSTEM -> SystemMessage(msg.content)
            MessageRole.TOOL -> ToolResponseMessage.builder()
                .responses(listOf(ToolResponseMessage.ToolResponse("", "tool", msg.content)))
                .build()
        }
    }

    /**
     * Load conversation history.
     */
    private fun loadConversationHistory(command: AgentCommand): List<Message> {
        if (command.conversationHistory.isNotEmpty()) {
            return command.conversationHistory.map { toSpringAiMessage(it) }
        }

        val sessionId = command.metadata["sessionId"] as? String ?: return emptyList()
        val memory = memoryStore?.get(sessionId) ?: return emptyList()

        return memory.getHistory().takeLast(properties.llm.maxConversationTurns * 2)
            .map { toSpringAiMessage(it) }
    }

    /**
     * Save conversation history.
     */
    private fun saveConversationHistory(command: AgentCommand, result: AgentResult) {
        val sessionId = command.metadata["sessionId"] as? String ?: return
        if (memoryStore == null) return

        memoryStore.addMessage(sessionId = sessionId, role = "user", content = command.userPrompt)

        if (result.success && result.content != null) {
            memoryStore.addMessage(sessionId = sessionId, role = "assistant", content = result.content)
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
     * Find a tool adapter by name from the registered tools.
     */
    private fun findToolAdapter(toolName: String, tools: List<Any>): ArcToolCallbackAdapter? {
        return tools.filterIsInstance<ArcToolCallbackAdapter>().firstOrNull { it.arcCallback.name == toolName }
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

            // System prompt (with RAG context if available)
            val systemPrompt = buildSystemPrompt(command.systemPrompt, ragContext)

            // Build initial messages
            val messages = mutableListOf<Message>()
            if (conversationHistory.isNotEmpty()) {
                messages.addAll(conversationHistory)
            }
            messages.add(UserMessage(command.userPrompt))

            val chatOptions = buildChatOptions(command, tools.isNotEmpty())
            var totalTokenUsage: TokenUsage? = null

            // ReAct loop: call LLM → execute tools → repeat until done or maxToolCalls
            while (true) {
                var requestSpec = chatClient.prompt()

                if (systemPrompt.isNotBlank()) {
                    requestSpec = requestSpec.system(systemPrompt)
                }

                requestSpec = requestSpec.messages(messages)
                requestSpec = requestSpec.options(chatOptions)

                if (tools.isNotEmpty()) {
                    requestSpec = requestSpec.tools(*tools.toTypedArray())
                }

                val response = kotlinx.coroutines.runInterruptible { requestSpec.call() }
                val chatResponse = response.chatResponse()

                // Accumulate token usage
                chatResponse?.metadata?.usage?.let {
                    val current = TokenUsage(
                        promptTokens = it.promptTokens.toInt(),
                        completionTokens = it.completionTokens.toInt(),
                        totalTokens = it.totalTokens.toInt()
                    )
                    totalTokenUsage = totalTokenUsage?.let { prev ->
                        TokenUsage(
                            promptTokens = prev.promptTokens + current.promptTokens,
                            completionTokens = prev.completionTokens + current.completionTokens,
                            totalTokens = prev.totalTokens + current.totalTokens
                        )
                    } ?: current
                }

                // Check for tool calls in the response
                val assistantOutput = chatResponse?.results?.firstOrNull()?.output
                val pendingToolCalls = assistantOutput?.toolCalls.orEmpty()

                if (pendingToolCalls.isEmpty() || tools.isEmpty()) {
                    // No tool calls — return final content
                    return AgentResult.success(
                        content = response.content() ?: "",
                        toolsUsed = ArrayList(toolsUsed),
                        tokenUsage = totalTokenUsage
                    )
                }

                // Add assistant message (with tool calls) to conversation
                messages.add(assistantOutput!!)

                // Execute each tool call
                val toolResponses = mutableListOf<ToolResponseMessage.ToolResponse>()

                for (toolCall in pendingToolCalls) {
                    if (totalToolCalls >= maxToolCalls) {
                        logger.warn { "maxToolCalls ($maxToolCalls) reached, stopping tool execution" }
                        val exhaustedResponse = ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(),
                            "Error: Maximum tool call limit ($maxToolCalls) reached"
                        )
                        toolResponses.add(exhaustedResponse)
                        continue
                    }

                    val toolName = toolCall.name()
                    val toolParams = parseJsonToMap(toolCall.arguments())

                    val toolCallContext = ToolCallContext(
                        agentContext = hookContext,
                        toolName = toolName,
                        toolParams = toolParams,
                        callIndex = totalToolCalls
                    )

                    // BeforeToolCallHook
                    if (hookExecutor != null) {
                        when (val hookResult = hookExecutor.executeBeforeToolCall(toolCallContext)) {
                            is HookResult.Reject -> {
                                logger.info { "Tool call $toolName rejected by hook: ${hookResult.reason}" }
                                toolResponses.add(
                                    ToolResponseMessage.ToolResponse(
                                        toolCall.id(), toolName,
                                        "Tool call rejected: ${hookResult.reason}"
                                    )
                                )
                                continue
                            }
                            else -> { /* continue */ }
                        }
                    }

                    // Execute the tool
                    val toolStartTime = System.currentTimeMillis()
                    val adapter = findToolAdapter(toolName, tools)
                    val toolOutput = if (adapter != null) {
                        try {
                            adapter.call(toolCall.arguments() ?: "{}")
                        } catch (e: Exception) {
                            logger.error(e) { "Tool $toolName execution failed" }
                            "Error: ${e.message}"
                        }
                    } else {
                        "Error: Tool '$toolName' not found"
                    }
                    val toolDurationMs = System.currentTimeMillis() - toolStartTime

                    totalToolCalls++
                    toolsUsed.add(toolName)

                    // AfterToolCallHook
                    hookExecutor?.executeAfterToolCall(
                        context = toolCallContext,
                        result = ToolCallResult(
                            success = !toolOutput.startsWith("Error:"),
                            output = toolOutput,
                            durationMs = toolDurationMs
                        )
                    )

                    agentMetrics.recordToolCall(toolName, toolDurationMs, !toolOutput.startsWith("Error:"))
                    logger.debug { "Tool $toolName executed in ${toolDurationMs}ms" }

                    toolResponses.add(
                        ToolResponseMessage.ToolResponse(toolCall.id(), toolName, toolOutput)
                    )
                }

                // Add tool results to conversation and loop back to LLM
                messages.add(
                    ToolResponseMessage.builder()
                        .responses(toolResponses)
                        .build()
                )

                // Safety: if all tool calls hit maxToolCalls limit, break to get final answer
                if (totalToolCalls >= maxToolCalls) {
                    logger.info { "maxToolCalls limit reached ($totalToolCalls/$maxToolCalls), requesting final answer" }
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // Rethrow to support withTimeout and structured concurrency
        } catch (e: Exception) {
            logger.error(e) { "LLM call with tools failed" }
            return AgentResult.failure(errorMessage = translateError(e))
        }
    }

    private fun translateError(e: Exception): String {
        val code = when {
            e.message?.contains("rate limit", ignoreCase = true) == true -> AgentErrorCode.RATE_LIMITED
            e.message?.contains("timeout", ignoreCase = true) == true -> AgentErrorCode.TIMEOUT
            e.message?.contains("context length", ignoreCase = true) == true -> AgentErrorCode.CONTEXT_TOO_LONG
            e.message?.contains("tool", ignoreCase = true) == true -> AgentErrorCode.TOOL_ERROR
            else -> AgentErrorCode.UNKNOWN
        }
        return errorMessageResolver.resolve(code, e.message)
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
