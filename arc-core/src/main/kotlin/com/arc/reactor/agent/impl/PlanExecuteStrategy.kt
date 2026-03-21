package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.ChatOptions

private val logger = KotlinLogging.logger {}

/**
 * 계획-실행(Plan-Execute) 전략 — 2단계 에이전트 실행.
 *
 * 복잡한 멀티스텝 질문에 대해 ReAct 루프가 maxToolCalls를 소진하는 문제를 해결한다.
 * LLM에게 먼저 실행 계획을 세우게 한 후, 계획대로 순차 실행하여 도구 호출 효율을 극대화한다.
 *
 * ## 실행 흐름
 * 1. **계획 단계**: 사용자 프롬프트 + 도구 목록을 LLM에 전달하여 JSON 계획 생성
 * 2. **실행 단계**: 계획의 각 단계를 [ToolCallOrchestrator]로 순차 실행
 * 3. **합성 단계**: 도구 실행 결과를 LLM에 전달하여 최종 응답 합성
 *
 * JSON 파싱 실패 시 REACT 모드로 폴백하지 않고 에러를 반환한다.
 *
 * @see com.arc.reactor.agent.model.AgentMode.PLAN_EXECUTE
 * @see ManualReActLoopExecutor ReAct 루프 (비교 대상)
 */
internal class PlanExecuteStrategy(
    private val toolCallOrchestrator: ToolCallOrchestrator,
    private val buildRequestSpec: (
        ChatClient, String, List<org.springframework.ai.chat.messages.Message>,
        ChatOptions, List<Any>
    ) -> ChatClient.ChatClientRequestSpec,
    private val callWithRetry: suspend (suspend () -> org.springframework.ai.chat.model.ChatResponse?) ->
        org.springframework.ai.chat.model.ChatResponse?,
    private val buildChatOptions: (AgentCommand, Boolean) -> ChatOptions,
    private val systemPromptBuilder: SystemPromptBuilder = SystemPromptBuilder()
) {

    /**
     * 계획-실행 모드의 메인 실행 메서드.
     *
     * @param command 에이전트 실행 명령
     * @param activeChatClient 사용할 ChatClient
     * @param systemPrompt 시스템 프롬프트
     * @param tools 활성 도구 목록
     * @param conversationHistory 대화 히스토리
     * @param hookContext Hook 컨텍스트
     * @param toolsUsed 사용된 도구 이름 누적 리스트
     * @param maxToolCalls 최대 도구 호출 횟수
     * @return 에이전트 실행 결과
     */
    suspend fun execute(
        command: AgentCommand,
        activeChatClient: ChatClient,
        systemPrompt: String,
        tools: List<Any>,
        conversationHistory: List<org.springframework.ai.chat.messages.Message>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        maxToolCalls: Int
    ): AgentResult {
        // 단계 1: 계획 생성
        val toolDescriptions = describeTools(tools)
        val plan = generatePlan(
            command, activeChatClient, systemPrompt, toolDescriptions
        )
        if (plan.isEmpty()) {
            logger.warn { "PLAN_EXECUTE: 빈 계획 생성, 직접 응답 시도" }
            return directAnswer(command, activeChatClient, systemPrompt)
        }
        logger.info { "PLAN_EXECUTE: ${plan.size}개 단계 계획 생성" }

        // 단계 2: 계획 순차 실행
        val results = executeSteps(
            plan, tools, hookContext, toolsUsed, maxToolCalls
        )

        // 단계 3: 최종 응답 합성
        return synthesize(command, activeChatClient, systemPrompt, results)
    }

    /** 도구 목록에서 이름과 설명을 추출하여 계획 프롬프트에 포함할 문자열을 생성한다. */
    private fun describeTools(tools: List<Any>): String {
        return tools.mapNotNull { tool ->
            when (tool) {
                is org.springframework.ai.tool.ToolCallback ->
                    "- ${tool.toolDefinition.name()}: ${tool.toolDefinition.description()}"
                else -> null
            }
        }.joinToString("\n")
    }

    /**
     * LLM에 계획 생성을 요청하고 JSON 배열을 파싱한다.
     *
     * 계획 단계 전용 시스템 프롬프트를 [SystemPromptBuilder.buildPlanningPrompt]로 생성하여
     * LLM이 도구 호출 계획만 JSON으로 출력하도록 지시한다.
     */
    private suspend fun generatePlan(
        command: AgentCommand,
        chatClient: ChatClient,
        systemPrompt: String,
        toolDescriptions: String
    ): List<PlanStep> {
        val planningSystemPrompt = systemPromptBuilder.buildPlanningPrompt(
            command.userPrompt, toolDescriptions
        )
        val messages = listOf(
            org.springframework.ai.chat.messages.UserMessage(command.userPrompt)
        )
        val chatOptions = buildChatOptions(command, false)
        val spec = buildRequestSpec(
            chatClient, planningSystemPrompt, messages,
            chatOptions, emptyList()
        )
        val response = callWithRetry {
            runInterruptible(Dispatchers.IO) { spec.call().chatResponse() }
        }
        val text = response?.results?.firstOrNull()?.output?.text.orEmpty()
        return parsePlan(text)
    }

    /** LLM 응답에서 JSON 계획 배열을 파싱한다. 실패 시 빈 리스트를 반환한다. */
    private fun parsePlan(text: String): List<PlanStep> {
        val jsonText = extractJsonArray(text)
        if (jsonText.isNullOrBlank()) {
            logger.warn { "PLAN_EXECUTE: JSON 배열 추출 실패" }
            return emptyList()
        }
        return try {
            objectMapper.readValue<List<PlanStep>>(jsonText)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "PLAN_EXECUTE: JSON 파싱 실패" }
            emptyList()
        }
    }

    /** 텍스트에서 최초의 JSON 배열([...])을 추출한다. */
    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** 계획의 각 단계를 순차적으로 실행한다. maxToolCalls를 초과하면 조기 종료한다. */
    private suspend fun executeSteps(
        plan: List<PlanStep>,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        maxToolCalls: Int
    ): List<StepResult> {
        val results = mutableListOf<StepResult>()
        var totalCalls = 0
        for (step in plan) {
            if (totalCalls >= maxToolCalls) {
                logger.info {
                    "PLAN_EXECUTE: maxToolCalls 도달 ($totalCalls/$maxToolCalls)"
                }
                break
            }
            val result = executeSingleStep(
                step, tools, hookContext, toolsUsed
            )
            results.add(result)
            totalCalls++
        }
        return results
    }

    /** 단일 계획 단계를 실행한다. */
    private suspend fun executeSingleStep(
        step: PlanStep,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>
    ): StepResult {
        logger.debug { "PLAN_EXECUTE 실행: ${step.description} (${step.tool})" }
        return try {
            val toolResult = toolCallOrchestrator.executeDirectToolCall(
                toolName = step.tool,
                toolParams = step.args,
                tools = tools,
                hookContext = hookContext,
                toolsUsed = toolsUsed
            )
            StepResult(step.description, step.tool, toolResult.output)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "PLAN_EXECUTE 단계 실패: ${step.tool}" }
            StepResult(step.description, step.tool, "Error: ${e.message}")
        }
    }

    /** 도구 실행 결과를 모아 LLM에 최종 응답 합성을 요청한다. */
    private suspend fun synthesize(
        command: AgentCommand,
        chatClient: ChatClient,
        systemPrompt: String,
        results: List<StepResult>
    ): AgentResult {
        val resultSummary = results.joinToString("\n\n") { r ->
            "[${r.tool}] ${r.description}\n${r.output.orEmpty()}"
        }
        val synthesisPrompt = buildString {
            append("사용자 요청: ${command.userPrompt}\n\n")
            append("수집된 정보:\n$resultSummary\n\n")
            append("위 정보를 바탕으로 사용자 요청에 답하세요.")
        }
        val messages = listOf(
            org.springframework.ai.chat.messages.UserMessage(synthesisPrompt)
        )
        val chatOptions = buildChatOptions(command, false)
        val spec = buildRequestSpec(
            chatClient, systemPrompt, messages, chatOptions, emptyList()
        )
        val response = callWithRetry {
            runInterruptible(Dispatchers.IO) { spec.call().chatResponse() }
        }
        val content = response?.results?.firstOrNull()?.output?.text.orEmpty()
        return AgentResult.success(content = content)
    }

    /** 도구 없이 LLM에 직접 응답을 요청한다 (빈 계획일 때 사용). */
    private suspend fun directAnswer(
        command: AgentCommand,
        chatClient: ChatClient,
        systemPrompt: String
    ): AgentResult {
        val messages = listOf(
            org.springframework.ai.chat.messages.UserMessage(command.userPrompt)
        )
        val chatOptions = buildChatOptions(command, false)
        val spec = buildRequestSpec(
            chatClient, systemPrompt, messages, chatOptions, emptyList()
        )
        val response = callWithRetry {
            runInterruptible(Dispatchers.IO) { spec.call().chatResponse() }
        }
        val content = response?.results?.firstOrNull()?.output?.text.orEmpty()
        return AgentResult.success(content = content)
    }

    /** LLM이 생성하는 계획의 단일 단계. */
    internal data class PlanStep(
        val tool: String = "",
        val args: Map<String, Any?> = emptyMap(),
        val description: String = ""
    )

    /** 실행된 단계의 결과. */
    private data class StepResult(
        val description: String,
        val tool: String,
        val output: String?
    )

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
