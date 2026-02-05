package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
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
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Spring AI 기반 Agent 실행기
 *
 * ReAct 패턴 구현:
 * - Guard: 5단계 가드레일
 * - Hook: 라이프사이클 확장점
 * - Tool: Spring AI Function Calling 통합
 * - Memory: 대화 컨텍스트 관리
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
    private val mcpToolCallbacks: () -> List<Any> = { emptyList() }
) : AgentExecutor {

    override suspend fun execute(command: AgentCommand): AgentResult {
        val startTime = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        val toolsUsed = mutableListOf<String>()

        // Hook Context 생성
        val hookContext = HookContext(
            runId = runId,
            userId = command.userId ?: "anonymous",
            userPrompt = command.userPrompt,
            toolsUsed = toolsUsed
        )

        try {
            // 1. Guard 검사
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

            // 3. 대화 히스토리 로드
            val conversationHistory = loadConversationHistory(command)

            // 4. Tool 선택 및 준비
            val selectedTools = selectAndPrepareTools(command.userPrompt)
            logger.debug { "Selected ${selectedTools.size} tools for execution" }

            // 5. Agent 실행 (Spring AI Function Calling)
            val result = executeWithTools(
                command = command,
                tools = selectedTools,
                conversationHistory = conversationHistory,
                hookContext = hookContext,
                toolsUsed = toolsUsed
            )

            // 6. 대화 히스토리 저장
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
        }
    }

    /**
     * 대화 히스토리 로드
     */
    private fun loadConversationHistory(command: AgentCommand): List<Message> {
        // Command에 히스토리가 있으면 사용
        if (command.conversationHistory.isNotEmpty()) {
            return command.conversationHistory.map { msg ->
                when (msg.role) {
                    MessageRole.USER -> UserMessage(msg.content)
                    MessageRole.ASSISTANT -> AssistantMessage(msg.content)
                    MessageRole.SYSTEM -> UserMessage(msg.content) // System은 별도 처리
                    MessageRole.TOOL -> UserMessage(msg.content)
                }
            }
        }

        // MemoryStore에서 로드
        val sessionId = command.metadata["sessionId"] as? String ?: return emptyList()
        val memory = memoryStore?.get(sessionId) ?: return emptyList()

        return memory.getHistory().takeLast(properties.llm.maxConversationTurns * 2).map { msg ->
            when (msg.role) {
                MessageRole.USER -> UserMessage(msg.content)
                MessageRole.ASSISTANT -> AssistantMessage(msg.content)
                MessageRole.SYSTEM -> UserMessage(msg.content)
                MessageRole.TOOL -> UserMessage(msg.content)
            }
        }
    }

    /**
     * 대화 히스토리 저장
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
     * Tool 선택 및 Spring AI ToolCallback으로 변환
     */
    private fun selectAndPrepareTools(userPrompt: String): List<Any> {
        // 1. LocalTool 인스턴스들 (Spring AI가 @Tool 어노테이션 추출)
        val localToolInstances = localTools.toList()

        // 2. MCP에서 로드된 Tool Callbacks
        val mcpTools = mcpToolCallbacks()

        // 3. 모든 Tool 합치기
        val allTools = localToolInstances + mcpTools

        // 4. 개수 제한
        return allTools.take(properties.maxToolsPerRequest)
    }

    /**
     * Spring AI ChatClient로 Tool 실행
     */
    private suspend fun executeWithTools(
        command: AgentCommand,
        tools: List<Any>,
        conversationHistory: List<Message>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>
    ): AgentResult {
        return try {
            // ChatClient 요청 구성
            var requestSpec = chatClient.prompt()

            // 시스템 프롬프트
            if (command.systemPrompt.isNotBlank()) {
                requestSpec = requestSpec.system(command.systemPrompt)
            }

            // 대화 히스토리 추가
            if (conversationHistory.isNotEmpty()) {
                requestSpec = requestSpec.messages(conversationHistory)
            }

            // 사용자 프롬프트
            requestSpec = requestSpec.user(command.userPrompt)

            // Tool 등록 (LocalTool 인스턴스는 @Tool 어노테이션으로 자동 변환)
            if (tools.isNotEmpty()) {
                requestSpec = requestSpec.tools(*tools.toTypedArray())
            }

            // LLM 호출
            val response = requestSpec.call()

            val content = response.content() ?: ""

            // 토큰 사용량 추출 (가능한 경우)
            val chatResponse = response.chatResponse()
            val tokenUsage = chatResponse?.metadata?.usage?.let {
                TokenUsage(
                    promptTokens = it.promptTokens.toInt(),
                    completionTokens = it.completionTokens.toInt(),
                    totalTokens = it.totalTokens.toInt()
                )
            }

            // Tool Call 추적 (ChatResponse에서 추출)
            chatResponse?.results?.forEach { generation ->
                generation.output?.toolCalls?.forEach { toolCall ->
                    val toolName = toolCall.name()
                    toolsUsed.add(toolName)

                    // Tool Call Context 생성 및 Hook 실행
                    val toolCallContext = ToolCallContext(
                        agentContext = hookContext,
                        toolName = toolName,
                        toolParams = parseToolArguments(toolCall.arguments()),
                        callIndex = toolsUsed.size - 1
                    )

                    // AfterToolCall Hook 실행
                    kotlinx.coroutines.runBlocking {
                        hookExecutor?.executeAfterToolCall(
                            context = toolCallContext,
                            result = ToolCallResult(
                                success = true,
                                output = "Tool executed",
                                durationMs = 0
                            )
                        )
                    }

                    logger.debug { "Tool called: $toolName" }
                }
            }

            AgentResult.success(
                content = content,
                toolsUsed = toolsUsed.toList(),
                tokenUsage = tokenUsage
            )
        } catch (e: Exception) {
            logger.error(e) { "LLM call with tools failed" }
            AgentResult.failure(errorMessage = translateError(e))
        }
    }

    /**
     * Tool 인자 파싱
     */
    private fun parseToolArguments(arguments: String?): Map<String, Any?> {
        if (arguments.isNullOrBlank()) return emptyMap()

        return try {
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                .readValue(arguments, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            logger.warn { "Failed to parse tool arguments: $arguments" }
            emptyMap()
        }
    }

    private fun translateError(e: Exception): String {
        return when {
            e.message?.contains("rate limit", ignoreCase = true) == true ->
                "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
            e.message?.contains("timeout", ignoreCase = true) == true ->
                "요청 시간이 초과되었습니다."
            e.message?.contains("context length", ignoreCase = true) == true ->
                "입력이 너무 깁니다. 내용을 줄여주세요."
            e.message?.contains("tool", ignoreCase = true) == true ->
                "도구 실행 중 오류가 발생했습니다: ${e.message}"
            else -> e.message ?: "알 수 없는 오류가 발생했습니다."
        }
    }
}
