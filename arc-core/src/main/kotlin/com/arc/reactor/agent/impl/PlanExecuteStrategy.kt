package com.arc.reactor.agent.impl

import com.arc.reactor.agent.budget.BudgetStatus
import com.arc.reactor.agent.budget.StepBudgetTracker
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.ExecutionStage
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.agent.metrics.recordError
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.plan.DefaultPlanValidator
import com.arc.reactor.agent.plan.PlanStep
import com.arc.reactor.agent.plan.PlanValidator
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions

private val logger = KotlinLogging.logger {}

/**
 * 계획-실행(Plan-Execute) 전략 — 3단계 에이전트 실행.
 *
 * 복잡한 멀티스텝 질문에 대해 ReAct 루프가 maxToolCalls를 소진하는 문제를 해결한다.
 * LLM에게 먼저 실행 계획을 세우게 한 후, 검증을 거쳐 순차 실행하여 도구 호출 효율을 극대화한다.
 *
 * ## 실행 흐름
 * 1. **계획 단계**: 사용자 프롬프트 + 도구 목록을 LLM에 전달하여 JSON 계획 생성
 * 2. **검증 단계**: [PlanValidator]로 계획의 도구 존재 여부 및 권한을 사전 검증
 * 3. **실행 단계**: 계획의 각 단계를 [ToolCallOrchestrator]로 순차 실행
 * 4. **합성 단계**: 도구 실행 결과를 LLM에 전달하여 최종 응답 합성
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
    private val callWithRetry: suspend (suspend () -> ChatResponse?) -> ChatResponse?,
    private val buildChatOptions: (AgentCommand, Boolean) -> ChatOptions,
    private val systemPromptBuilder: SystemPromptBuilder = SystemPromptBuilder(),
    private val planValidator: PlanValidator = DefaultPlanValidator(),
    private val toolApprovalPolicy: ToolApprovalPolicy? = null,
    /**
     * R254: JSON 계획 파싱 실패를 `execution.error{stage="parsing"}`에 자동 기록.
     * 기본값 NoOp으로 backward compat. `parsePlan()`이 fail-open으로 빈 리스트를 반환하는데,
     * 이 메트릭 없이는 LLM이 유효하지 않은 JSON 계획을 자주 생성해도 관측이 어렵다.
     */
    private val evaluationMetricsCollector: EvaluationMetricsCollector = NoOpEvaluationMetricsCollector,
    /**
     * R309: generatePlan / synthesize / directAnswer LLM 호출의 토큰 사용량을 기록한다.
     *
     * 기본값 no-op으로 backward compat. 기존 구현은 `ManualReActLoopExecutor`/`StreamingReActLoopExecutor`와
     * 달리 토큰 사용량을 메트릭에 전달하지 않아 PLAN_EXECUTE 모드의 LLM 비용이 관측 파이프라인에서
     * 누락되었다. `StepBudgetTracker`는 도구 호출 토큰(`hookContext.metadata["tokensUsed"]`)만 보고
     * plan/synthesize LLM 호출 토큰은 추적되지 않았다.
     */
    private val recordTokenUsage: (TokenUsage, Map<String, Any>) -> Unit = { _, _ -> }
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
     * @param budgetTracker 토큰 예산 추적기 (null이면 예산 추적 비활성)
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
        maxToolCalls: Int,
        budgetTracker: StepBudgetTracker? = null,
        allowedTools: Set<String>? = null
    ): AgentResult {
        val toolDescriptions = describeTools(tools)
        val plan = generatePlan(
            command, activeChatClient, systemPrompt, toolDescriptions, hookContext
        )
        if (plan.isEmpty()) {
            logger.warn { "PLAN_EXECUTE: 빈 계획 생성, 직접 응답 시도" }
            return directAnswer(command, activeChatClient, systemPrompt, hookContext)
        }
        logger.info { "PLAN_EXECUTE: ${plan.size}개 단계 계획 생성" }

        val validationResult = planValidator.validate(
            steps = plan,
            availableToolNames = extractToolNames(tools),
            toolApprovalPolicy = toolApprovalPolicy
        )
        if (!validationResult.valid) {
            return buildValidationFailure(validationResult.errors)
        }

        val results = executeSteps(
            plan, tools, hookContext, toolsUsed, maxToolCalls, budgetTracker, allowedTools
        )
        // R308 fix: 실행된 단계가 **전부 실패**했을 때 synthesis LLM 호출을 우회하고 즉시 실패 반환.
        // 기존 동작은 "Error: ..." 문자열만 있는 results로 synthesize를 호출 → LLM 환각 응답 생성.
        // 주의: 빈 results(예: maxToolCalls=0 edge case)는 여전히 synthesize로 진행해야 함 —
        // 이는 호출자의 의도적 설정이며 직접 응답 경로로 간주한다.
        if (results.isNotEmpty() && results.none { it.success }) {
            logger.warn {
                "PLAN_EXECUTE: 모든 실행 단계 실패 (${results.size}/${plan.size}) — synthesize 우회"
            }
            return AgentResult.failure(
                errorMessage = "모든 계획 단계 실행 실패",
                errorCode = AgentErrorCode.TOOL_ERROR
            )
        }
        return synthesize(command, activeChatClient, systemPrompt, results, hookContext)
    }

    /**
     * 검증 실패 시 실패 결과를 생성한다.
     *
     * R326 fix: 기존 구현은 `errors.joinToString("; ")`를 그대로 `errorMessage`에 포함하여
     * LLM이 생성한 도구 이름이 HTTP 응답에 노출됐다 (예: `"단계 1: 도구 'sql_injection_payload'
     * 이(가) 등록된 도구 목록에 존재하지 않습니다."`). `PlanValidator`의 errors는 LLM 출력에서
     * 유래한 `step.tool` 값을 포함하므로 prompt injection으로 조작 가능 → 공격자가 도구 이름에
     * 임의 문자열을 삽입하여 클라이언트에 에코. CLAUDE.md Gotcha #9(HTTP 응답 메시지 노출 금지)의
     * LLM 출력 경로 확장판. 일반 메시지만 `errorMessage`에 포함하고 상세 내역은 서버 로그에만 기록.
     */
    private fun buildValidationFailure(errors: List<String>): AgentResult {
        val errorDetail = errors.joinToString("; ")
        // 상세 errorDetail은 서버 로그에만 기록 (LLM 생성 도구 이름 포함 가능)
        logger.warn { "PLAN_EXECUTE: 계획 검증 실패 — $errorDetail" }
        return AgentResult.failure(
            errorMessage = "계획 검증 실패: 요청된 도구를 사용할 수 없습니다.",
            errorCode = AgentErrorCode.PLAN_VALIDATION_FAILED
        )
    }

    /** 도구 목록에서 이름과 설명을 추출하여 계획 프롬프트에 포함할 문자열을 생성한다. */
    private fun describeTools(tools: List<Any>): String {
        return tools.mapNotNull { tool ->
            when (tool) {
                is org.springframework.ai.tool.ToolCallback ->
                    "- ${tool.toolDefinition.name()}: " +
                        tool.toolDefinition.description()
                else -> null
            }
        }.joinToString("\n")
    }

    /** 도구 목록에서 이름 집합을 추출한다. */
    private fun extractToolNames(tools: List<Any>): Set<String> {
        return tools.mapNotNull { tool ->
            when (tool) {
                is org.springframework.ai.tool.ToolCallback ->
                    tool.toolDefinition.name()
                else -> null
            }
        }.toSet()
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
        toolDescriptions: String,
        hookContext: HookContext
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
        // R309: plan generation LLM 호출 토큰 사용량을 메트릭에 전달
        ReActLoopUtils.emitTokenUsageMetric(
            response?.metadata, hookContext, recordTokenUsage
        )
        val text = response?.results?.firstOrNull()
            ?.output?.text.orEmpty()
        return parsePlan(text)
    }

    /** LLM 응답에서 JSON 계획 배열을 파싱한다. 실패 시 빈 리스트를 반환한다. */
    internal fun parsePlan(text: String): List<PlanStep> {
        val jsonText = extractJsonArray(text)
        if (jsonText.isNullOrBlank()) {
            logger.warn { "PLAN_EXECUTE: JSON 배열 추출 실패" }
            return emptyList()
        }
        return try {
            objectMapper.readValue<List<PlanStep>>(jsonText)
        } catch (e: Exception) {
            e.throwIfCancellation()
            // R254: PLAN_EXECUTE JSON 계획 파싱 실패를 PARSING stage로 기록
            evaluationMetricsCollector.recordError(ExecutionStage.PARSING, e)
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

    /**
     * 계획의 각 단계를 순차적으로 실행한다.
     * maxToolCalls 초과 또는 토큰 예산 소진 시 조기 종료한다.
     */
    private suspend fun executeSteps(
        plan: List<PlanStep>,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        maxToolCalls: Int,
        budgetTracker: StepBudgetTracker?,
        allowedTools: Set<String>?
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
                step, tools, hookContext, toolsUsed, allowedTools
            )
            results.add(result)
            totalCalls++

            // 각 단계의 LLM 호출은 이미 StepBudgetTracker에 기록됨
            // (ToolCallOrchestrator -> ChatClient가 hookContext.metadata["tokensUsed"]에 누적)
            if (budgetTracker != null) {
                val status = checkBudgetFromHookContext(
                    budgetTracker, hookContext, step.tool
                )
                if (status == BudgetStatus.EXHAUSTED) {
                    logger.warn {
                        "PLAN_EXECUTE: 토큰 예산 소진, 남은 단계 건너뜀" +
                            " (${results.size}/${plan.size} 완료)"
                    }
                    break
                }
            }
        }
        return results
    }

    /**
     * HookContext 메타데이터에서 누적 토큰 사용량을 읽어 예산을 추적한다.
     * 토큰 정보가 없으면 [BudgetStatus.OK]를 반환한다.
     */
    private fun checkBudgetFromHookContext(
        tracker: StepBudgetTracker,
        hookContext: HookContext,
        stepName: String
    ): BudgetStatus {
        // hookContext.metadata["tokensUsed"]는 ToolCallOrchestrator가 갱신
        val tokensUsed = hookContext.metadata["tokensUsed"]
        if (tokensUsed is Int && tokensUsed > tracker.totalConsumed()) {
            val delta = tokensUsed - tracker.totalConsumed()
            return tracker.trackStep(
                step = "plan-step-$stepName",
                inputTokens = delta,
                outputTokens = 0
            )
        }
        return if (tracker.isExhausted()) BudgetStatus.EXHAUSTED
            else BudgetStatus.OK
    }

    /**
     * 단일 계획 단계를 실행한다.
     *
     * R326 fix: `allowedTools`(intent 기반 도구 허용 목록)를 `executeDirectToolCall`로 전달한다.
     * 기존 구현은 PLAN_EXECUTE 경로에서 `allowedTools`를 누락하여 ReAct 경로에서만 적용되던 intent
     * 허용 목록이 PLAN_EXECUTE에서는 무시되었다. 공격자가 `command.mode=PLAN_EXECUTE`로 전환한 후
     * intent가 차단한 도구를 LLM 계획에 포함시키면 그대로 실행되는 우회 경로.
     */
    private suspend fun executeSingleStep(
        step: PlanStep,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        allowedTools: Set<String>?
    ): StepResult {
        logger.debug { "PLAN_EXECUTE 실행: ${step.description} (${step.tool})" }
        return try {
            val toolResult = toolCallOrchestrator.executeDirectToolCall(
                toolName = step.tool,
                toolParams = step.args,
                tools = tools,
                hookContext = hookContext,
                toolsUsed = toolsUsed,
                allowedTools = allowedTools
            )
            StepResult(
                description = step.description,
                tool = step.tool,
                output = toolResult.output,
                success = toolResult.success
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            // R308 fix: e.javaClass.simpleName을 tool output 문자열에 포함하면 synthesis를 거쳐
            // 사용자 응답에 클래스명이 노출될 수 있다 (CLAUDE.md Gotcha #9 계열). 내부 클래스명은
            // 서버 로그에만 남기고 tool output에는 불투명 "TOOL_ERROR" 문자열만 사용한다.
            logger.warn(e) {
                "PLAN_EXECUTE 단계 실패: ${step.tool} (exception=${e.javaClass.simpleName})"
            }
            StepResult(
                description = step.description,
                tool = step.tool,
                output = "Error: TOOL_ERROR",
                success = false
            )
        }
    }

    /**
     * 도구 실행 결과를 모아 LLM에 최종 응답 합성을 요청한다.
     *
     * **R333 fix (cross_source_synthesis)**: 실패한 step의 `output`은 `"Error: TOOL_ERROR"`
     * 같은 내부 마커 문자열인데, 기존 구현은 `r.output.orEmpty()`로 성공 결과와 **동등하게**
     * LLM 프롬프트에 주입했다. R308에서 "전부 실패" 케이스는 차단했지만, **일부만 실패**한
     * 혼합 케이스는 여전히 synthesize로 진입한다(line 127의 `results.none { it.success }`
     * 가드는 "전부 실패"만 차단). 결과: LLM이 `"Error: TOOL_ERROR"`를 실제 source 데이터로
     * 오해하거나, 실패 step을 없는 것으로 착각하여 **환각 응답**을 생성할 수 있었다.
     * 예: 3단계 계획 중 Bitbucket만 실패 → LLM에게 `"[bitbucket_get_pr] ... Error: TOOL_ERROR"`
     * 가 성공 데이터와 뒤섞여 전달 → LLM이 존재하지 않는 PR을 근거로 답변.
     *
     * **수정**: `r.success`와 `r.output` 공백 여부를 확인해 3가지 섹션으로 분류한다.
     * - 성공 + non-blank output: 원본 output을 그대로 사용
     * - 성공 + blank output: 명시적 "데이터 없음" 마커
     * - 실패(`success=false`): 명시적 "[실패] 답변 근거 금지" 마커, 원본 에러 문자열 노출 금지
     *
     * LLM은 성공 source와 실패 source를 구분하고 부분 성공임을 인지할 수 있다. 에러 내부
     * 표현(`TOOL_ERROR` 등)은 LLM/사용자 응답 경로에서 완전히 제거되어 sanitization 원칙(
     * CLAUDE.md Gotcha #9 계열) 유지.
     */
    private suspend fun synthesize(
        command: AgentCommand,
        chatClient: ChatClient,
        systemPrompt: String,
        results: List<StepResult>,
        hookContext: HookContext
    ): AgentResult {
        val resultSummary = results.joinToString("\n\n") { r ->
            val header = "[${r.tool}] ${r.description}"
            val body = when {
                !r.success -> "[실패] 이 단계는 실행에 실패했습니다. 답변 근거로 사용하지 마세요."
                r.output.isNullOrBlank() ->
                    "[데이터 없음] 이 단계는 결과를 반환하지 않았습니다."
                else -> r.output
            }
            "$header\n$body"
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
        // R309: synthesize LLM 호출 토큰 사용량을 메트릭에 전달
        ReActLoopUtils.emitTokenUsageMetric(
            response?.metadata, hookContext, recordTokenUsage
        )
        // R308 fix: LLM이 null 또는 빈 content를 반환하면 기존 구현은 AgentResult.success("")를
        // 반환하여 호출자가 LLM 실패와 정상 빈 응답을 구분할 수 없었다. INVALID_RESPONSE로 명시적
        // 실패 처리한다.
        val content = response?.results?.firstOrNull()?.output?.text
        if (content.isNullOrBlank()) {
            logger.warn { "PLAN_EXECUTE synthesize: LLM이 null/빈 응답 반환" }
            return AgentResult.failure(
                errorMessage = "LLM synthesize 응답 없음",
                errorCode = AgentErrorCode.INVALID_RESPONSE
            )
        }
        return AgentResult.success(content = content)
    }

    /** 도구 없이 LLM에 직접 응답을 요청한다 (빈 계획일 때 사용). */
    private suspend fun directAnswer(
        command: AgentCommand,
        chatClient: ChatClient,
        systemPrompt: String,
        hookContext: HookContext
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
        // R309: directAnswer LLM 호출 토큰 사용량을 메트릭에 전달
        ReActLoopUtils.emitTokenUsageMetric(
            response?.metadata, hookContext, recordTokenUsage
        )
        // R308 fix: synthesize()와 동일 — null/빈 응답을 INVALID_RESPONSE 실패로 승격.
        val content = response?.results?.firstOrNull()?.output?.text
        if (content.isNullOrBlank()) {
            logger.warn { "PLAN_EXECUTE directAnswer: LLM이 null/빈 응답 반환" }
            return AgentResult.failure(
                errorMessage = "LLM 직접 응답 없음",
                errorCode = AgentErrorCode.INVALID_RESPONSE
            )
        }
        return AgentResult.success(content = content)
    }

    /**
     * 실행된 단계의 결과.
     *
     * R308 fix: `success` 필드 추가 — 기존 구현은 error 문자열 prefix("Error: ")로 실패를
     * 구분해야 했으나 이는 synthesis LLM 프롬프트와 섞여 판별이 부정확했다.
     */
    private data class StepResult(
        val description: String,
        val tool: String,
        val output: String?,
        val success: Boolean = true
    )

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
