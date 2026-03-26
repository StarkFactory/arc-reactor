package com.arc.reactor.agent.impl

import com.arc.reactor.agent.budget.BudgetStatus
import com.arc.reactor.agent.budget.StepBudgetTracker
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * 수동 ReAct 루프 실행기 — LLM 호출과 Tool 실행을 반복하는 핵심 루프 본체.
 *
 * Spring AI의 내장 Tool 실행 루프를 사용하지 않고 직접 루프를 관리합니다.
 * 이를 통해 maxToolCalls 제한, Hook 실행, 메트릭 기록 등을 세밀하게 제어합니다.
 *
 * 루프 흐름:
 * 1. 메시지 트리밍 (컨텍스트 윈도우 초과 방지)
 * 2. LLM 호출 (재시도 포함)
 * 3. Tool Call 감지 -> 있으면 [ToolCallOrchestrator]로 병렬 실행
 * 4. 결과 메시지(AssistantMessage + ToolResponseMessage) 추가
 * 5. maxToolCalls 도달 시 Tool 비활성화 후 최종 답변 요청
 * 6. Tool Call이 없으면 응답 검증 후 반환
 *
 * @see SpringAiAgentExecutor 이 실행기를 생성하고 호출하는 상위 클래스
 * @see ToolCallOrchestrator 병렬 도구 실행 위임 대상
 * @see ConversationMessageTrimmer 컨텍스트 윈도우 내 메시지 트리밍
 */
internal class ManualReActLoopExecutor(
    private val messageTrimmer: ConversationMessageTrimmer,
    private val toolCallOrchestrator: ToolCallOrchestrator,
    private val buildRequestSpec: (
        ChatClient,
        String,
        List<Message>,
        ChatOptions,
        List<Any>
    ) -> ChatClient.ChatClientRequestSpec,
    private val callWithRetry: suspend (
        suspend () -> ChatResponse?
    ) -> ChatResponse?,
    private val buildChatOptions: (AgentCommand, Boolean) -> ChatOptions,
    private val validateAndRepairResponse: suspend (
        String,
        ResponseFormat,
        AgentCommand,
        TokenUsage?,
        List<String>
    ) -> AgentResult,
    private val recordTokenUsage: (TokenUsage, Map<String, Any>) -> Unit,
    private val tracer: ArcReactorTracer = NoOpArcReactorTracer()
) {

    /**
     * ReAct 루프를 실행하여 에이전트 결과를 반환합니다.
     *
     * @param command 에이전트 실행 명령
     * @param activeChatClient 사용할 ChatClient (모델별 분기 적용 완료)
     * @param systemPrompt RAG 컨텍스트와 응답 형식이 포함된 시스템 프롬프트
     * @param initialTools 선택된 Tool 목록 (maxToolCalls=0이면 비활성화됨)
     * @param conversationHistory 기존 대화 히스토리
     * @param hookContext Hook/메트릭용 실행 컨텍스트
     * @param toolsUsed 실행된 도구 이름을 누적하는 리스트
     * @param allowedTools Intent 기반 Tool 허용 목록 (null이면 전체 허용)
     * @param maxToolCalls 최대 Tool 호출 횟수
     * @param budgetTracker 토큰 예산 추적기 (null이면 예산 추적 비활성)
     * @return 에이전트 실행 결과
     * @see ToolCallOrchestrator.executeInParallel
     */
    suspend fun execute(
        command: AgentCommand,
        activeChatClient: ChatClient,
        systemPrompt: String,
        initialTools: List<Any>,
        conversationHistory: List<Message>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        allowedTools: Set<String>?,
        maxToolCalls: Int,
        budgetTracker: StepBudgetTracker? = null
    ): AgentResult {
        // ── 루프 상태 초기화 ──
        var totalToolCalls = 0
        val totalToolCallsCounter = AtomicInteger(0) // 루프 밖 1회 할당 (매 반복 재생성 방지)
        var llmCallIndex = 0
        var activeTools = if (maxToolCalls > 0) initialTools else emptyList()
        var chatOptions = buildChatOptions(command, activeTools.isNotEmpty())
        var totalTokenUsage: TokenUsage? = null
        var totalLlmDurationMs = 0L
        var totalToolDurationMs = 0L
        var hadToolError = false
        var textRetryCount = 0

        // ── 대화 히스토리 + 현재 사용자 메시지 조합 ──
        val messages = mutableListOf<Message>()
        if (conversationHistory.isNotEmpty()) {
            messages.addAll(conversationHistory)
        }
        messages.add(MediaConverter.buildUserMessage(command.userPrompt, command.media))

        // ── ReAct 루프 시작 — Tool Call이 없을 때까지 반복 ──
        while (true) {
            // 단계 A: 컨텍스트 윈도우 초과 방지를 위한 메시지 트리밍
            messageTrimmer.trim(
                messages, systemPrompt,
                activeTools.size * ReActLoopUtils.TOKENS_PER_TOOL_DEFINITION
            )

            // 단계 B: LLM 호출 (재시도 포함)
            val requestSpec = buildRequestSpec(
                activeChatClient, systemPrompt, messages, chatOptions, activeTools
            )
            val llmStart = System.nanoTime()
            val chatResponse = callLlmWithTracing(requestSpec, llmCallIndex)
            llmCallIndex++
            totalLlmDurationMs += (System.nanoTime() - llmStart) / 1_000_000

            totalTokenUsage = accumulateTokenUsage(chatResponse, totalTokenUsage)
            emitTokenUsageMetric(chatResponse, hookContext)

            // 단계 B-2: 토큰 예산 추적 — EXHAUSTED 시 루프 종료
            if (trackBudgetAndCheckExhausted(
                    chatResponse, budgetTracker, llmCallIndex, hookContext
                )
            ) {
                recordLoopDurations(
                    hookContext, totalLlmDurationMs, totalToolDurationMs
                )
                val tracker = budgetTracker
                    ?: error("budgetTracker는 null일 수 없음: 예산 소진 판정 후")
                return buildBudgetExhaustedResult(
                    tracker, totalTokenUsage, toolsUsed
                )
            }

            // 단계 C: Tool Call 존재 여부 확인
            val assistantOutput = chatResponse?.results?.firstOrNull()?.output
            val pendingToolCalls = assistantOutput?.toolCalls.orEmpty()
            if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
                if (shouldRetryAfterToolError(
                        hadToolError, pendingToolCalls, activeTools,
                        messages, textRetryCount
                    )
                ) {
                    textRetryCount++
                    hadToolError = false
                    continue
                }
                recordLoopDurations(hookContext, totalLlmDurationMs, totalToolDurationMs)
                return validateAndRepairResponse(
                    assistantOutput?.text.orEmpty(),
                    command.responseFormat, command,
                    totalTokenUsage, ArrayList(toolsUsed)
                )
            }
            textRetryCount = 0

            // assistantOutput은 pendingToolCalls가 비어있지 않으면 항상 non-null
            // (null이면 toolCalls도 null → orEmpty() → isEmpty → 위에서 이미 반환됨)
            val safeAssistantOutput = requireNotNull(assistantOutput) {
                "assistantOutput must be non-null when pendingToolCalls is non-empty"
            }

            // 단계 D: Tool 병렬 실행 — ToolCallOrchestrator에 위임
            totalToolCallsCounter.set(totalToolCalls)
            val toolStart = System.nanoTime()
            val toolResponses = executeToolsWithTracing(
                pendingToolCalls, activeTools, hookContext, toolsUsed,
                totalToolCallsCounter, maxToolCalls, allowedTools, chatOptions,
                totalToolCalls
            )
            totalToolDurationMs += (System.nanoTime() - toolStart) / 1_000_000
            totalToolCalls = totalToolCallsCounter.get()

            // 단계 E: AssistantMessage + ToolResponseMessage 쌍으로 추가
            appendToolMessagePair(messages, safeAssistantOutput, toolResponses)

            // 단계 E-2: 도구 에러 시 재시도 힌트 주입
            hadToolError = ReActLoopUtils.hasToolError(toolResponses)
            if (totalToolCalls < maxToolCalls) {
                ReActLoopUtils.injectToolErrorRetryHint(toolResponses, messages)
            }

            // 단계 F: maxToolCalls 도달 시 Tool 비활성화 — 무한 루프 방지 필수
            if (totalToolCalls >= maxToolCalls) {
                logger.info {
                    "maxToolCalls reached ($totalToolCalls/$maxToolCalls), final answer"
                }
                activeTools = emptyList()
                chatOptions = buildChatOptions(command, false)
                messages.add(
                    ReActLoopUtils.buildMaxToolCallsMessage(totalToolCalls, maxToolCalls)
                )
            }
        }
    }

    // ── private 메서드: 토큰 예산 추적 ──

    /**
     * LLM 호출 후 토큰 예산을 추적하고 EXHAUSTED 여부를 반환한다.
     * tracker가 null이면 항상 false를 반환한다.
     */
    private fun trackBudgetAndCheckExhausted(
        chatResponse: ChatResponse?,
        tracker: StepBudgetTracker?,
        llmCallIndex: Int,
        hookContext: HookContext
    ): Boolean {
        tracker ?: return false
        val usage = chatResponse?.metadata?.usage ?: return false
        val status = tracker.trackStep(
            step = "llm-call-$llmCallIndex",
            inputTokens = usage.promptTokens.toInt(),
            outputTokens = usage.completionTokens.toInt()
        )
        writeBudgetMetadata(hookContext, tracker, status)
        return status == BudgetStatus.EXHAUSTED
    }

    /** 예산 소진 시 반환할 AgentResult를 생성한다. */
    private fun buildBudgetExhaustedResult(
        tracker: StepBudgetTracker,
        totalTokenUsage: TokenUsage?,
        toolsUsed: List<String>
    ): AgentResult {
        return AgentResult(
            success = false,
            content = BUDGET_EXHAUSTED_MESSAGE,
            errorCode = AgentErrorCode.BUDGET_EXHAUSTED,
            errorMessage = BUDGET_EXHAUSTED_MESSAGE,
            toolsUsed = toolsUsed.toList(),
            tokenUsage = totalTokenUsage,
            metadata = mapOf(
                "tokensUsed" to tracker.totalConsumed(),
                "budgetStatus" to BudgetStatus.EXHAUSTED.name
            )
        )
    }

    /** 예산 상태를 HookContext 메타데이터에 기록한다. */
    private fun writeBudgetMetadata(
        hookContext: HookContext,
        tracker: StepBudgetTracker,
        status: BudgetStatus
    ) {
        hookContext.metadata["tokensUsed"] = tracker.totalConsumed()
        hookContext.metadata["budgetStatus"] = status.name
    }

    // ── private 메서드: LLM 호출 ──

    /** LLM 호출을 tracer span으로 감싸서 실행한다. */
    private suspend fun callLlmWithTracing(
        requestSpec: ChatClient.ChatClientRequestSpec,
        llmCallIndex: Int
    ): ChatResponse? {
        val llmSpan = tracer.startSpan(
            "arc.agent.llm.call",
            mapOf("llm.call.index" to llmCallIndex.toString())
        )
        return try {
            callWithRetry {
                runInterruptible(Dispatchers.IO) { requestSpec.call().chatResponse() }
            }
        } finally {
            llmSpan.close()
        }
    }

    // ── private 메서드: 토큰 사용량 기록 ──

    /** LLM 응답의 토큰 사용량을 메트릭 콜백으로 전달한다. 식별자만 포함하는 최소 메타데이터를 사용한다. */
    private fun emitTokenUsageMetric(
        chatResponse: ChatResponse?,
        hookContext: HookContext
    ) {
        val meta = chatResponse?.metadata ?: return
        val usage = meta.usage ?: return
        val tokenMetadata = buildMap<String, Any>(3) {
            put("runId", hookContext.runId)
            meta.model?.let { put("model", it) }
            hookContext.metadata["tenantId"]?.let { put("tenantId", it) }
        }
        recordTokenUsage(
            TokenUsage(
                promptTokens = usage.promptTokens.toInt(),
                completionTokens = usage.completionTokens.toInt(),
                totalTokens = usage.totalTokens.toInt()
            ),
            tokenMetadata
        )
    }

    // ── private 메서드: 도구 실행 ──

    /** 도구 호출을 tracer span으로 감싸서 병렬 실행한다. */
    private suspend fun executeToolsWithTracing(
        pendingToolCalls: List<AssistantMessage.ToolCall>,
        activeTools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        totalToolCallsCounter: AtomicInteger,
        maxToolCalls: Int,
        allowedTools: Set<String>?,
        chatOptions: ChatOptions,
        currentTotalToolCalls: Int
    ): List<ToolResponseMessage.ToolResponse> {
        val toolSpans = pendingToolCalls.mapIndexed { idx, tc ->
            tracer.startSpan(
                "arc.agent.tool.call",
                mapOf(
                    "tool.name" to tc.name(),
                    "tool.call.index" to (currentTotalToolCalls + idx).toString()
                )
            )
        }
        return try {
            toolCallOrchestrator.executeInParallel(
                pendingToolCalls, activeTools, hookContext, toolsUsed,
                totalToolCallsCounter, maxToolCalls, allowedTools,
                normalizeToolResponseToJson =
                    ReActLoopUtils.shouldNormalizeToolResponses(chatOptions)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Tool execution failed, skipping message pair" }
            throw e
        } finally {
            for (span in toolSpans) {
                span.close()
            }
        }
    }

    // ── private 메서드: 메시지 쌍 조립 ──

    /**
     * AssistantMessage + ToolResponseMessage 쌍을 메시지 리스트에 추가한다.
     * (메시지 쌍 무결성 필수)
     */
    private fun appendToolMessagePair(
        messages: MutableList<Message>,
        assistantOutput: AssistantMessage,
        toolResponses: List<ToolResponseMessage.ToolResponse>
    ) {
        messages.add(assistantOutput)
        messages.add(
            ToolResponseMessage.builder()
                .responses(toolResponses)
                .build()
        )
    }

    // ── private 메서드: 루프 종료 조건 ──

    /** 도구 에러 직후 텍스트 응답이면 강화 힌트를 주입하고 루프 재시도 여부를 반환한다. */
    private fun shouldRetryAfterToolError(
        hadToolError: Boolean,
        pendingToolCalls: List<AssistantMessage.ToolCall>,
        activeTools: List<Any>,
        messages: MutableList<Message>,
        textRetryCount: Int
    ): Boolean {
        if (!hadToolError || pendingToolCalls.isNotEmpty() || activeTools.isEmpty()) {
            return false
        }
        return ReActLoopUtils.injectForceRetryHintIfNeeded(messages, textRetryCount)
    }

    /** 루프 종료 시 LLM/Tool 소요 시간을 HookContext에 기록한다. */
    private fun recordLoopDurations(
        hookContext: HookContext,
        totalLlmDurationMs: Long,
        totalToolDurationMs: Long
    ) {
        hookContext.metadata["llmDurationMs"] = totalLlmDurationMs
        hookContext.metadata["toolDurationMs"] = totalToolDurationMs
        recordStageTiming(hookContext, "llm_calls", totalLlmDurationMs)
        recordStageTiming(hookContext, "tool_execution", totalToolDurationMs)
    }

    /** 여러 LLM 호출의 Token 사용량을 누적합니다. */
    private fun accumulateTokenUsage(
        chatResponse: ChatResponse?,
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

    companion object {
        internal const val BUDGET_EXHAUSTED_MESSAGE =
            "토큰 예산이 초과되었습니다. 응답이 불완전할 수 있습니다."
    }
}
