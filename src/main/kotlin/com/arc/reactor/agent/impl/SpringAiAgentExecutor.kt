package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.agent.model.TokenUsage
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
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Spring AI-Based Agent Executor
 *
 * ReAct pattern implementation:
 * - Guard: 5-stage guardrail pipeline
 * - Hook: lifecycle extension points
 * - Tool: Spring AI Function Calling integration
 * - Memory: conversation context management
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
    private val errorMessageResolver: ErrorMessageResolver = DefaultErrorMessageResolver()
) : AgentExecutor {

    override suspend fun execute(command: AgentCommand): AgentResult {
        val startTime = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        val toolsUsed = mutableListOf<String>()

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
                    return AgentResult.failure(
                        errorMessage = guardResult.reason,
                        durationMs = System.currentTimeMillis() - startTime
                    )
                }
            }

            // 2. Before Agent Start Hook
            if (hookExecutor != null) {
                when (val hookResult = hookExecutor.executeBeforeAgentStart(hookContext)) {
                    is HookResult.Reject -> {
                        return AgentResult.failure(
                            errorMessage = hookResult.reason,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }
                    is HookResult.PendingApproval -> {
                        return AgentResult.failure(
                            errorMessage = "Pending approval: ${hookResult.message}",
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }
                    else -> { /* continue */ }
                }
            }

            // 3. Load conversation history
            val conversationHistory = loadConversationHistory(command)

            // 4. Select and prepare tools
            val selectedTools = selectAndPrepareTools(command.userPrompt)
            logger.debug { "Selected ${selectedTools.size} tools for execution" }

            // 5. Agent execution (Spring AI Function Calling)
            val result = executeWithTools(
                command = command,
                tools = selectedTools,
                conversationHistory = conversationHistory,
                hookContext = hookContext,
                toolsUsed = toolsUsed
            )

            // 6. Save conversation history
            saveConversationHistory(command, result)

            // 7. After Agent Complete Hook
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(
                    success = result.success,
                    response = result.content,
                    errorMessage = result.errorMessage,
                    toolsUsed = toolsUsed
                )
            )

            return result.copy(
                durationMs = System.currentTimeMillis() - startTime
            )

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

            return AgentResult.failure(
                errorMessage = e.message ?: "Unknown error",
                durationMs = System.currentTimeMillis() - startTime
            )
        } finally {
            MDC.remove("runId")
            MDC.remove("userId")
            MDC.remove("sessionId")
        }
    }

    /**
     * Load conversation history.
     */
    private fun loadConversationHistory(command: AgentCommand): List<Message> {
        // Use history from command if available
        if (command.conversationHistory.isNotEmpty()) {
            return command.conversationHistory.map { msg ->
                when (msg.role) {
                    MessageRole.USER -> UserMessage(msg.content)
                    MessageRole.ASSISTANT -> AssistantMessage(msg.content)
                    MessageRole.SYSTEM -> SystemMessage(msg.content)
                    MessageRole.TOOL -> UserMessage(msg.content)
                }
            }
        }

        // Load from MemoryStore
        val sessionId = command.metadata["sessionId"] as? String ?: return emptyList()
        val memory = memoryStore?.get(sessionId) ?: return emptyList()

        return memory.getHistory().takeLast(properties.llm.maxConversationTurns * 2).map { msg ->
            when (msg.role) {
                MessageRole.USER -> UserMessage(msg.content)
                MessageRole.ASSISTANT -> AssistantMessage(msg.content)
                MessageRole.SYSTEM -> SystemMessage(msg.content)
                MessageRole.TOOL -> UserMessage(msg.content)
            }
        }
    }

    /**
     * Save conversation history.
     */
    private fun saveConversationHistory(command: AgentCommand, result: AgentResult) {
        val sessionId = command.metadata["sessionId"] as? String ?: return
        if (memoryStore == null) return

        memoryStore.addMessage(
            sessionId = sessionId,
            role = "user",
            content = command.userPrompt
        )

        if (result.success && result.content != null) {
            memoryStore.addMessage(
                sessionId = sessionId,
                role = "assistant",
                content = result.content
            )
        }
    }

    /**
     * Select and convert tools to Spring AI ToolCallbacks.
     */
    private fun selectAndPrepareTools(userPrompt: String): List<Any> {
        // 1. LocalTool instances (Spring AI extracts @Tool annotations)
        val localToolInstances = localTools.toList()

        // 2. Tool Callbacks loaded from MCP
        val mcpTools = mcpToolCallbacks()

        // 3. Merge all tools
        val allTools = localToolInstances + mcpTools

        // 4. Apply count limit
        return allTools.take(properties.maxToolsPerRequest)
    }

    /**
     * Execute tools with Spring AI ChatClient.
     */
    private suspend fun executeWithTools(
        command: AgentCommand,
        tools: List<Any>,
        conversationHistory: List<Message>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>
    ): AgentResult {
        return try {
            // Build ChatClient request
            var requestSpec = chatClient.prompt()

            // System prompt
            if (command.systemPrompt.isNotBlank()) {
                requestSpec = requestSpec.system(command.systemPrompt)
            }

            // Add conversation history
            if (conversationHistory.isNotEmpty()) {
                requestSpec = requestSpec.messages(conversationHistory)
            }

            // User prompt
            requestSpec = requestSpec.user(command.userPrompt)

            // Register tools (LocalTool instances auto-converted via @Tool annotation)
            if (tools.isNotEmpty()) {
                requestSpec = requestSpec.tools(*tools.toTypedArray())
            }

            // LLM call
            val response = requestSpec.call()

            val content = response.content() ?: ""

            // Extract token usage (if available)
            val chatResponse = response.chatResponse()
            val tokenUsage = chatResponse?.metadata?.usage?.let {
                TokenUsage(
                    promptTokens = it.promptTokens.toInt(),
                    completionTokens = it.completionTokens.toInt(),
                    totalTokens = it.totalTokens.toInt()
                )
            }

            // Track tool calls (extract from ChatResponse)
            for (generation in chatResponse?.results.orEmpty()) {
                for (toolCall in generation.output.toolCalls.orEmpty()) {
                    val toolName = toolCall.name()
                    toolsUsed.add(toolName)

                    // Create ToolCallContext and execute hook
                    val toolCallContext = ToolCallContext(
                        agentContext = hookContext,
                        toolName = toolName,
                        toolParams = parseToolArguments(toolCall.arguments()),
                        callIndex = toolsUsed.size - 1
                    )

                    // AfterToolCall Hook execution
                    hookExecutor?.executeAfterToolCall(
                        context = toolCallContext,
                        result = ToolCallResult(
                            success = true,
                            output = "Tool executed",
                            durationMs = 0
                        )
                    )

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

    /**
     * Parse tool arguments.
     */
    private fun parseToolArguments(arguments: String?): Map<String, Any?> {
        if (arguments.isNullOrBlank()) return emptyMap()

        return try {
            val typeRef = object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
            objectMapper.readValue(arguments, typeRef)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse tool arguments: $arguments" }
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
