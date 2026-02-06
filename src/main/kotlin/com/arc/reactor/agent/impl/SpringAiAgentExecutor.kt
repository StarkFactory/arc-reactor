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
import mu.KotlinLogging
import org.slf4j.MDC
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
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
    private val properties: AgentProperties,
    private val localTools: List<LocalTool> = emptyList(),
    private val toolCallbacks: List<ToolCallback> = emptyList(),
    private val toolSelector: ToolSelector? = null,
    private val guard: RequestGuard? = null,
    private val hookExecutor: HookExecutor? = null,
    private val memoryStore: MemoryStore? = null,
    private val mcpToolCallbacks: () -> List<Any> = { emptyList() },
    private val errorMessageResolver: ErrorMessageResolver = DefaultErrorMessageResolver(),
    private val agentMetrics: AgentMetrics = NoOpAgentMetrics(),
    private val ragPipeline: RagPipeline? = null
) : AgentExecutor {

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
            // 1. Guard check
            if (guard != null && command.userId != null) {
                val guardResult = guard.guard(
                    GuardCommand(
                        userId = command.userId,
                        text = command.userPrompt
                    )
                )
                if (guardResult is GuardResult.Rejected) {
                    agentMetrics.recordGuardRejection(
                        stage = guardResult.stage ?: "unknown",
                        reason = guardResult.reason
                    )
                    val failResult = AgentResult.failure(
                        errorMessage = guardResult.reason,
                        durationMs = System.currentTimeMillis() - startTime
                    )
                    agentMetrics.recordExecution(failResult)
                    return failResult
                }
            }

            // 2. Before Agent Start Hook
            if (hookExecutor != null) {
                when (val hookResult = hookExecutor.executeBeforeAgentStart(hookContext)) {
                    is HookResult.Reject -> {
                        val failResult = AgentResult.failure(
                            errorMessage = hookResult.reason,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                        agentMetrics.recordExecution(failResult)
                        return failResult
                    }
                    is HookResult.PendingApproval -> {
                        val failResult = AgentResult.failure(
                            errorMessage = "Pending approval: ${hookResult.message}",
                            durationMs = System.currentTimeMillis() - startTime
                        )
                        agentMetrics.recordExecution(failResult)
                        return failResult
                    }
                    else -> { /* continue */ }
                }
            }

            // 3. Load conversation history
            val conversationHistory = loadConversationHistory(command)

            // 4. RAG: Retrieve context if enabled
            val ragContext = retrieveRagContext(command.userPrompt)

            // 5. Select and prepare tools (respecting AgentMode)
            val selectedTools = if (command.mode == AgentMode.STANDARD) {
                emptyList() // STANDARD mode: no tools
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

            val finalResult = result.copy(
                durationMs = System.currentTimeMillis() - startTime
            )
            agentMetrics.recordExecution(finalResult)
            return finalResult

        } catch (e: Exception) {
            logger.error(e) { "Agent execution failed" }

            // Error Hook
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(
                    success = false,
                    errorMessage = e.message
                )
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

    override fun executeStream(command: AgentCommand): Flow<String> = flow {
        val runId = UUID.randomUUID().toString()
        val hookContext = HookContext(
            runId = runId,
            userId = command.userId ?: "anonymous",
            userPrompt = command.userPrompt,
            toolsUsed = mutableListOf()
        )

        // 1. Guard check
        if (guard != null && command.userId != null) {
            val guardResult = guard.guard(
                GuardCommand(userId = command.userId, text = command.userPrompt)
            )
            if (guardResult is GuardResult.Rejected) {
                agentMetrics.recordGuardRejection(
                    stage = guardResult.stage ?: "unknown",
                    reason = guardResult.reason
                )
                emit(guardResult.reason)
                return@flow
            }
        }

        // 2. Before hook
        if (hookExecutor != null) {
            when (val hookResult = hookExecutor.executeBeforeAgentStart(hookContext)) {
                is HookResult.Reject -> {
                    emit(hookResult.reason)
                    return@flow
                }
                is HookResult.PendingApproval -> {
                    emit("Pending approval: ${hookResult.message}")
                    return@flow
                }
                else -> { /* continue */ }
            }
        }

        // 3. Build request
        var requestSpec = chatClient.prompt()

        // RAG context
        val ragContext = retrieveRagContext(command.userPrompt)
        val systemPrompt = if (ragContext != null) {
            "${command.systemPrompt}\n\n[Retrieved Context]\n$ragContext"
        } else {
            command.systemPrompt
        }
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
     * Convert MessageRole to Spring AI Message.
     */
    private fun toSpringAiMessage(msg: com.arc.reactor.agent.model.Message): Message {
        return when (msg.role) {
            MessageRole.USER -> UserMessage(msg.content)
            MessageRole.ASSISTANT -> AssistantMessage(msg.content)
            MessageRole.SYSTEM -> SystemMessage(msg.content)
            MessageRole.TOOL -> UserMessage(msg.content)
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
        val mcpCallbacks = mcpToolCallbacks().filterIsInstance<ToolCallback>()
        val allCallbacks = toolCallbacks + mcpCallbacks

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
     * Execute tools with Spring AI ChatClient.
     */
    private suspend fun executeWithTools(
        command: AgentCommand,
        tools: List<Any>,
        conversationHistory: List<Message>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        ragContext: String? = null
    ): AgentResult {
        return try {
            var requestSpec = chatClient.prompt()

            // System prompt (with RAG context if available)
            val systemPrompt = if (ragContext != null) {
                "${command.systemPrompt}\n\n[Retrieved Context]\n$ragContext"
            } else {
                command.systemPrompt
            }
            if (systemPrompt.isNotBlank()) {
                requestSpec = requestSpec.system(systemPrompt)
            }

            // Conversation history
            if (conversationHistory.isNotEmpty()) {
                requestSpec = requestSpec.messages(conversationHistory)
            }

            // User prompt
            requestSpec = requestSpec.user(command.userPrompt)

            // Register tools
            if (tools.isNotEmpty()) {
                requestSpec = requestSpec.tools(*tools.toTypedArray())
            }

            // LLM call
            val response = requestSpec.call()
            val content = response.content() ?: ""

            // Extract token usage
            val chatResponse = response.chatResponse()
            val tokenUsage = chatResponse?.metadata?.usage?.let {
                TokenUsage(
                    promptTokens = it.promptTokens.toInt(),
                    completionTokens = it.completionTokens.toInt(),
                    totalTokens = it.totalTokens.toInt()
                )
            }

            // Track tool calls
            for (generation in chatResponse?.results.orEmpty()) {
                for (toolCall in generation.output.toolCalls.orEmpty()) {
                    val toolName = toolCall.name()
                    toolsUsed.add(toolName)

                    val toolCallContext = ToolCallContext(
                        agentContext = hookContext,
                        toolName = toolName,
                        toolParams = parseToolArguments(toolCall.arguments()),
                        callIndex = toolsUsed.size - 1
                    )

                    hookExecutor?.executeAfterToolCall(
                        context = toolCallContext,
                        result = ToolCallResult(success = true, output = "Tool executed", durationMs = 0)
                    )

                    agentMetrics.recordToolCall(toolName, 0, true)
                    logger.debug { "Tool called: $toolName" }
                }
            }

            AgentResult.success(
                content = content,
                toolsUsed = ArrayList(toolsUsed),
                tokenUsage = tokenUsage
            )
        } catch (e: Exception) {
            logger.error(e) { "LLM call with tools failed" }
            AgentResult.failure(errorMessage = translateError(e))
        }
    }

    private fun parseToolArguments(arguments: String?): Map<String, Any?> {
        if (arguments.isNullOrBlank()) return emptyMap()
        return try {
            val typeRef = object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
            objectMapper.readValue(arguments, typeRef)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse tool arguments" }
            emptyMap()
        }
    }

    companion object {
        private val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
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
    private val arcCallback: ToolCallback
) : org.springframework.ai.tool.ToolCallback {

    private val toolDefinition = ToolDefinition.builder()
        .name(arcCallback.name)
        .description(arcCallback.description)
        .inputSchema("""{"type": "object", "properties": {}}""")
        .build()

    override fun getToolDefinition(): ToolDefinition = toolDefinition

    override fun getToolMetadata(): ToolMetadata = ToolMetadata.builder().build()

    override fun call(toolInput: String): String {
        val args = try {
            val typeRef = object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
            objectMapper.readValue(toolInput, typeRef)
        } catch (e: Exception) {
            emptyMap<String, Any?>()
        }
        return kotlinx.coroutines.runBlocking {
            arcCallback.call(args)?.toString() ?: ""
        }
    }

    companion object {
        private val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    }
}
