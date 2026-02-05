package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Spring AI 기반 Agent 실행기
 *
 * ReAct 패턴 구현:
 * - Guard: 5단계 가드레일
 * - Hook: 라이프사이클 확장점
 * - Tool: 동적 Tool 선택 및 실행
 */
class SpringAiAgentExecutor(
    private val chatClient: ChatClient,
    private val properties: AgentProperties,
    private val localTools: List<LocalTool> = emptyList(),
    private val toolCallbacks: List<ToolCallback> = emptyList(),
    private val toolSelector: ToolSelector? = null,
    private val guard: RequestGuard? = null,
    private val hookExecutor: HookExecutor? = null,
    private val mcpToolCallbacks: () -> List<ToolCallback> = { emptyList() }
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

            // 3. Tool 선택
            val selectedTools = selectTools(command.userPrompt)
            logger.debug { "Selected ${selectedTools.size} tools for execution" }

            // 4. Agent 실행
            val result = executeAgent(command, selectedTools, toolsUsed)

            // 5. After Agent Complete Hook
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

    private fun selectTools(userPrompt: String): List<ToolCallback> {
        val allTools = toolCallbacks + mcpToolCallbacks()

        val selected = if (toolSelector != null) {
            toolSelector.select(userPrompt, allTools)
        } else {
            allTools
        }

        // Tool 개수 제한
        return selected.take(properties.maxToolsPerRequest)
    }

    private suspend fun executeAgent(
        command: AgentCommand,
        tools: List<ToolCallback>,
        toolsUsed: MutableList<String>
    ): AgentResult {
        return try {
            // Spring AI ChatClient 사용
            // 실제 Tool 통합은 Spring AI의 Function Calling API에 따라 구현
            val response = chatClient.prompt()
                .system(command.systemPrompt)
                .user(command.userPrompt)
                .call()

            val content = response.content() ?: ""

            AgentResult.success(
                content = content,
                toolsUsed = toolsUsed.toList()
            )
        } catch (e: Exception) {
            logger.error(e) { "LLM call failed" }
            AgentResult.failure(errorMessage = translateError(e))
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
            else -> e.message ?: "알 수 없는 오류가 발생했습니다."
        }
    }
}
