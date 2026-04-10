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
        val state = initLoopState(command, maxToolCalls, initialTools)
        val messages = buildInitialMessages(conversationHistory, command)
        while (true) {
            trimMessages(messages, systemPrompt, state.activeTools)
            val response = callLlmAndAccumulate(
                activeChatClient, systemPrompt, messages, state, hookContext
            )
            handleBudgetCheck(response, budgetTracker, state, hookContext, toolsUsed)
                ?.let { return it }
            when (val action = resolveToolCallAction(
                response, state, messages, command, toolsUsed, hookContext
            )) {
                is LoopAction.Return -> return action.result
                is LoopAction.RetryWithoutTools -> continue
                is LoopAction.ExecuteTools -> processToolCalls(
                    action, response, state, messages, command,
                    hookContext, toolsUsed, maxToolCalls, allowedTools
                )
            }
        }
    }

    // ── private 메서드: 루프 초기화 ──

    /** 루프 상태를 초기화한다. */
    private fun initLoopState(
        command: AgentCommand,
        maxToolCalls: Int,
        initialTools: List<Any>
    ): ReActLoopState {
        val hasTools = maxToolCalls > 0 && initialTools.isNotEmpty()
        return ReActLoopState(
            maxToolCalls, initialTools, buildChatOptions(command, hasTools)
        )
    }

    /** 대화 히스토리 + 현재 사용자 메시지를 결합한 초기 메시지 리스트를 생성한다. */
    private fun buildInitialMessages(
        conversationHistory: List<Message>,
        command: AgentCommand
    ): MutableList<Message> {
        val messages = mutableListOf<Message>()
        if (conversationHistory.isNotEmpty()) {
            messages.addAll(conversationHistory)
        }
        messages.add(
            MediaConverter.buildUserMessage(command.userPrompt, command.media)
        )
        return messages
    }

    // ── private 메서드: 단계 A — 메시지 트리밍 ──

    /** 컨텍스트 윈도우 초과 방지를 위해 메시지를 트리밍한다. */
    private suspend fun trimMessages(
        messages: MutableList<Message>,
        systemPrompt: String,
        activeTools: List<Any>
    ) {
        messageTrimmer.trim(
            messages, systemPrompt,
            activeTools.size * ReActLoopUtils.TOKENS_PER_TOOL_DEFINITION
        )
    }

    // ── private 메서드: 단계 B — LLM 호출 + 토큰 누적 ──

    /** LLM을 호출하고 토큰 사용량을 누적·기록한다. */
    private suspend fun callLlmAndAccumulate(
        activeChatClient: ChatClient,
        systemPrompt: String,
        messages: List<Message>,
        state: ReActLoopState,
        hookContext: HookContext
    ): ChatResponse? {
        val requestSpec = buildRequestSpec(
            activeChatClient, systemPrompt, messages,
            state.chatOptions, state.activeTools
        )
        val llmStart = System.nanoTime()
        val chatResponse = callLlmWithTracing(requestSpec, state.llmCallIndex)
        state.llmCallIndex++
        state.totalLlmDurationMs += (System.nanoTime() - llmStart) / 1_000_000
        state.totalTokenUsage = accumulateTokenUsage(
            chatResponse, state.totalTokenUsage
        )
        emitTokenUsageMetric(chatResponse, hookContext)
        return chatResponse
    }

    // ── private 메서드: 단계 B-2 — 예산 소진 확인 ──

    /** 토큰 예산을 확인하고, 소진 시 결과를 반환한다. null이면 계속 진행. */
    private fun handleBudgetCheck(
        chatResponse: ChatResponse?,
        budgetTracker: StepBudgetTracker?,
        state: ReActLoopState,
        hookContext: HookContext,
        toolsUsed: List<String>
    ): AgentResult? {
        if (!trackBudgetAndCheckExhausted(
                chatResponse, budgetTracker, state.llmCallIndex, hookContext
            )
        ) return null
        recordLoopDurations(
            hookContext, state.totalLlmDurationMs, state.totalToolDurationMs
        )
        val tracker = budgetTracker
            ?: error("budgetTracker는 null일 수 없음: 예산 소진 판정 후")
        return buildBudgetExhaustedResult(
            tracker, state.totalTokenUsage, toolsUsed
        )
    }

    // ── private 메서드: 단계 C — Tool Call 분기 판정 ──

    /**
     * LLM 응답을 분석하여 루프 제어 액션을 결정한다.
     * - Tool Call 존재 → [LoopAction.ExecuteTools]
     * - 도구 에러 후 재시도 → [LoopAction.RetryWithoutTools]
     * - 최종 응답 → [LoopAction.Return]
     */
    private suspend fun resolveToolCallAction(
        chatResponse: ChatResponse?,
        state: ReActLoopState,
        messages: MutableList<Message>,
        command: AgentCommand,
        toolsUsed: MutableList<String>,
        hookContext: HookContext
    ): LoopAction {
        val output = chatResponse?.results?.firstOrNull()?.output
        val pending = output?.toolCalls.orEmpty()
        if (pending.isNotEmpty() && state.activeTools.isNotEmpty()) {
            state.textRetryCount = 0
            return LoopAction.ExecuteTools(requireNotNull(output))
        }
        if (shouldRetryAfterToolError(
                state.hadToolError, pending,
                state.activeTools, messages, state.textRetryCount
            )) {
            state.textRetryCount++
            state.hadToolError = false
            return LoopAction.RetryWithoutTools
        }
        // LLM이 tool_calls 없이 도구 호출 의도를 텍스트로만 표현한 경우 재시도
        val outputText = output?.text.orEmpty()
        val intentDetected = looksLikeUnexecutedToolIntent(outputText)
        if (intentDetected) {
            logger.info { "미실행 도구 의도 감지: pending=${pending.size}, active=${state.activeTools.size}, retry=${state.textRetryCount}, text=${outputText.take(80)}" }
        }
        if (pending.isEmpty() && state.activeTools.isNotEmpty() &&
            state.textRetryCount < 1 && intentDetected
        ) {
            logger.info { "도구 호출 의도가 텍스트로만 표현됨 — 재시도 (textRetryCount=${state.textRetryCount})" }
            state.textRetryCount++
            return LoopAction.RetryWithoutTools
        }
        recordLoopDurations(hookContext, state.totalLlmDurationMs, state.totalToolDurationMs)
        return buildFinalResult(output, command, state, toolsUsed)
    }

    /** 최종 응답을 검증·복구하여 LoopAction.Return으로 감싼다. */
    private suspend fun buildFinalResult(
        output: AssistantMessage?,
        command: AgentCommand,
        state: ReActLoopState,
        toolsUsed: List<String>
    ): LoopAction.Return {
        val result = validateAndRepairResponse(
            output?.text.orEmpty(), command.responseFormat,
            command, state.totalTokenUsage, ArrayList(toolsUsed)
        )
        return LoopAction.Return(result)
    }

    // ── private 메서드: 단계 D-F — 도구 실행 + 후처리 ──

    /** 도구를 실행하고 메시지 쌍 추가, 에러 힌트, maxToolCalls 검사를 수행한다. */
    private suspend fun processToolCalls(
        action: LoopAction.ExecuteTools,
        chatResponse: ChatResponse?,
        state: ReActLoopState,
        messages: MutableList<Message>,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        maxToolCalls: Int,
        allowedTools: Set<String>?
    ) {
        val pendingToolCalls = chatResponse?.results?.firstOrNull()
            ?.output?.toolCalls.orEmpty()
        // 사전 중복 차단: 이미 같은 서명으로 성공한 호출은 실행하지 않고 캐시된 결과를 재사용한다.
        val (newToolCalls, cachedResponses) = splitDuplicateToolCalls(pendingToolCalls, state)
        val executedResponses = if (newToolCalls.isEmpty()) {
            emptyList()
        } else {
            executeAndRecordToolsFiltered(
                newToolCalls, state, hookContext,
                toolsUsed, maxToolCalls, allowedTools
            )
        }
        val toolResponses = mergeToolResponsesInOrder(pendingToolCalls, cachedResponses, executedResponses)
        appendToolMessagePair(messages, action.assistantOutput, toolResponses)
        // 성공한 도구 서명/결과 기록 + 사후 힌트 주입
        recordSucceededAndMaybeHint(pendingToolCalls, toolResponses, cachedResponses, state, messages)
        applyPostToolHints(toolResponses, state, messages)
        enforceMaxToolCalls(state, command, messages)
    }

    /**
     * 들어온 toolCalls를 "신규"와 "이미 성공한 서명으로 캐시된 결과"로 분리한다.
     * 캐시된 결과는 원본 tool call id를 사용하여 ToolResponse를 즉시 생성한다.
     *
     * 두 가지 중복 패턴을 모두 차단한다:
     * 1. 이전 iteration에서 이미 성공한 호출과 동일한 서명 → [state.succeededToolResults] 재사용
     * 2. 같은 iteration 내 병렬 호출에서 동일 서명 중복 → 첫 번째만 실행, 나머지는 결과 복제
     */
    private fun splitDuplicateToolCalls(
        pendingToolCalls: List<AssistantMessage.ToolCall>,
        state: ReActLoopState
    ): Pair<List<AssistantMessage.ToolCall>, List<ToolResponseMessage.ToolResponse>> {
        if (pendingToolCalls.isEmpty()) return pendingToolCalls to emptyList()
        val newCalls = mutableListOf<AssistantMessage.ToolCall>()
        val cached = mutableListOf<ToolResponseMessage.ToolResponse>()
        // iteration 내 동일 서명 tc 추적 — 첫 번째 tc의 id를 기록해 나머지는 그 결과에 묶는다.
        val sigToFirstCallId = mutableMapOf<String, String>()
        for (tc in pendingToolCalls) {
            val sig = buildToolSignature(tc)
            // 1. 이전 iteration 캐시 확인
            val prevResult = state.succeededToolResults[sig]
            if (prevResult != null) {
                logger.info { "중복 도구 호출 사전 차단(prev): ${tc.name()} — 이전 결과 재사용" }
                cached.add(ToolResponseMessage.ToolResponse(tc.id(), tc.name(), prevResult))
                continue
            }
            // 2. 같은 iteration 내 중복 확인 — 첫 번째만 실행 대상, 나머지는 placeholder 생성
            val firstId = sigToFirstCallId[sig]
            if (firstId != null) {
                logger.info { "중복 도구 호출 사전 차단(same-iter): ${tc.name()} — 동일 iteration 재사용" }
                // 실행 이후 첫 번째 결과로 채워질 placeholder — 여기서는 마커만 기록
                cached.add(
                    ToolResponseMessage.ToolResponse(
                        tc.id(),
                        tc.name(),
                        SAME_ITER_DUPLICATE_MARKER + firstId
                    )
                )
            } else {
                sigToFirstCallId[sig] = tc.id()
                newCalls.add(tc)
            }
        }
        return newCalls to cached
    }

    /**
     * 캐시된 응답과 실제 실행 응답을 원본 toolCall 순서대로 병합한다.
     * same-iteration 중복 마커(SAME_ITER_DUPLICATE_MARKER)를 만나면,
     * 참조된 첫 번째 호출의 실제 결과로 본문을 치환한다.
     */
    private fun mergeToolResponsesInOrder(
        pendingToolCalls: List<AssistantMessage.ToolCall>,
        cachedResponses: List<ToolResponseMessage.ToolResponse>,
        executedResponses: List<ToolResponseMessage.ToolResponse>
    ): List<ToolResponseMessage.ToolResponse> {
        if (cachedResponses.isEmpty()) return executedResponses
        if (executedResponses.isEmpty() && cachedResponses.none {
                it.responseData().startsWith(SAME_ITER_DUPLICATE_MARKER)
            }) {
            return cachedResponses
        }
        val executedById = executedResponses.associateBy { it.id() }
        val resolved = cachedResponses.map { r ->
            val body = r.responseData()
            if (body.startsWith(SAME_ITER_DUPLICATE_MARKER)) {
                val firstId = body.removePrefix(SAME_ITER_DUPLICATE_MARKER)
                val sourceBody = executedById[firstId]?.responseData() ?: body
                ToolResponseMessage.ToolResponse(r.id(), r.name(), sourceBody)
            } else {
                r
            }
        }
        val byId = (resolved + executedResponses).associateBy { it.id() }
        return pendingToolCalls.mapNotNull { byId[it.id()] }
    }


    /**
     * 실제 실행된 toolCalls만 대상으로 executeAndRecordTools를 호출한다.
     * 기존 `executeAndRecordTools`는 chatResponse 전체를 다시 파싱하므로,
     * 사전 차단으로 걸러낸 호출을 우회하기 위해 별도 경로를 제공한다.
     */
    private suspend fun executeAndRecordToolsFiltered(
        newToolCalls: List<AssistantMessage.ToolCall>,
        state: ReActLoopState,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        maxToolCalls: Int,
        allowedTools: Set<String>?
    ): List<ToolResponseMessage.ToolResponse> {
        state.totalToolCallsCounter.set(state.totalToolCalls)
        val toolStart = System.nanoTime()
        val toolResponses = executeToolsWithTracing(
            newToolCalls, state.activeTools, hookContext, toolsUsed,
            state.totalToolCallsCounter, maxToolCalls, allowedTools,
            state.chatOptions, state.totalToolCalls
        )
        state.totalToolDurationMs += (System.nanoTime() - toolStart) / 1_000_000
        state.totalToolCalls = state.totalToolCallsCounter.get()
        return toolResponses
    }

    /**
     * 새로 실행된 성공 도구의 서명/결과를 기록하고,
     * 캐시 재사용이 발생했거나 원본 toolCalls에서 사후 중복이 감지되면 힌트를 주입한다.
     */
    private fun recordSucceededAndMaybeHint(
        toolCalls: List<AssistantMessage.ToolCall>,
        allResponses: List<ToolResponseMessage.ToolResponse>,
        cachedResponses: List<ToolResponseMessage.ToolResponse>,
        state: ReActLoopState,
        messages: MutableList<Message>
    ) {
        val errorIds = allResponses
            .filter { it.responseData().startsWith(ReActLoopUtils.TOOL_ERROR_PREFIX) }
            .map { it.id() }
            .toSet()
        val responseById = allResponses.associateBy { it.id() }
        val cachedIds = cachedResponses.map { it.id() }.toSet()
        val duplicateNames = mutableListOf<String>()
        for (tc in toolCalls) {
            if (tc.id() in errorIds) continue
            val sig = buildToolSignature(tc)
            if (sig in state.succeededToolSignatures) {
                duplicateNames.add(tc.name())
                continue
            }
            state.succeededToolSignatures.add(sig)
            if (tc.id() !in cachedIds) {
                responseById[tc.id()]?.responseData()?.let { body ->
                    state.succeededToolResults[sig] = body
                }
            }
        }
        // 캐시 재사용이 일어났거나 동일 서명 재호출이 감지되면 힌트 주입 + 도구 비활성화
        if (cachedResponses.isNotEmpty() || duplicateNames.isNotEmpty()) {
            val names = (cachedResponses.map { it.name() } + duplicateNames)
                .distinct()
                .joinToString(", ")
            logger.info { "동일 도구 중복 호출 감지: $names — 종합 응답 유도 힌트 주입" }
            messages.add(
                org.springframework.ai.chat.messages.UserMessage(
                    "[System] 동일한 도구($names)를 동일한 파라미터로 이미 호출했습니다. " +
                        "추가 호출 없이 기존 결과를 종합하여 응답하세요."
                )
            )
            state.activeTools = emptyList()
        }
    }

    /** 도구 호출 서명을 생성한다 (이름 + 정규화된 인자). */
    private fun buildToolSignature(tc: AssistantMessage.ToolCall): String {
        return "${tc.name()}::${tc.arguments().orEmpty().trim()}"
    }

    // ── private 메서드: 도구 실행 기록 ──

    /** 도구를 병렬 실행하고 소요 시간을 상태에 누적한다. */
    private suspend fun executeAndRecordTools(
        chatResponse: ChatResponse?,
        state: ReActLoopState,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        maxToolCalls: Int,
        allowedTools: Set<String>?
    ): List<ToolResponseMessage.ToolResponse> {
        val pendingToolCalls = chatResponse?.results?.firstOrNull()
            ?.output?.toolCalls.orEmpty()
        state.totalToolCallsCounter.set(state.totalToolCalls)
        val toolStart = System.nanoTime()
        val toolResponses = executeToolsWithTracing(
            pendingToolCalls, state.activeTools, hookContext, toolsUsed,
            state.totalToolCallsCounter, maxToolCalls, allowedTools,
            state.chatOptions, state.totalToolCalls
        )
        state.totalToolDurationMs += (System.nanoTime() - toolStart) / 1_000_000
        state.totalToolCalls = state.totalToolCallsCounter.get()
        return toolResponses
    }

    // ── private 메서드: 단계 E-2 — 도구 에러 힌트 주입 ──

    /** 도구 에러 여부를 기록하고, maxToolCalls 미만이면 재시도 힌트를 주입한다. */
    private fun applyPostToolHints(
        toolResponses: List<ToolResponseMessage.ToolResponse>,
        state: ReActLoopState,
        messages: MutableList<Message>
    ) {
        state.hadToolError = ReActLoopUtils.hasToolError(toolResponses)
        if (state.totalToolCalls < state.maxToolCalls) {
            ReActLoopUtils.injectToolErrorRetryHint(toolResponses, messages)
        }
    }

    // ── private 메서드: 단계 F — maxToolCalls 도달 시 도구 비활성화 ──

    /** maxToolCalls 도달 시 도구를 비활성화하고 최종 답변 요청 메시지를 주입한다. */
    private fun enforceMaxToolCalls(
        state: ReActLoopState,
        command: AgentCommand,
        messages: MutableList<Message>
    ) {
        if (state.totalToolCalls < state.maxToolCalls) return
        logger.info {
            "maxToolCalls reached " +
                "(${state.totalToolCalls}/${state.maxToolCalls}), final answer"
        }
        state.activeTools = emptyList()
        state.chatOptions = buildChatOptions(command, false)
        messages.add(
            ReActLoopUtils.buildMaxToolCallsMessage(
                state.totalToolCalls, state.maxToolCalls
            )
        )
    }

    // ── 루프 제어 시그널 ──

    /** ReAct 루프 반복에서의 분기 결정을 표현하는 sealed 인터페이스. */
    private sealed interface LoopAction {
        /** 최종 결과 반환 (루프 종료). */
        data class Return(val result: AgentResult) : LoopAction

        /** 도구 에러 후 재시도 (도구 실행 단계 건너뜀). */
        data object RetryWithoutTools : LoopAction

        /** Tool Call 존재 — 도구 실행 단계로 진행. */
        data class ExecuteTools(
            val assistantOutput: AssistantMessage
        ) : LoopAction
    }

    // ── 루프 상태 컨테이너 ──

    /**
     * ReAct 루프의 가변 상태를 보관하는 내부 컨테이너.
     * 루프 반복마다 읽기/쓰기되는 변수를 하나로 묶어 메서드 간 전달을 단순화한다.
     */
    private class ReActLoopState(
        val maxToolCalls: Int,
        initialTools: List<Any>,
        initialChatOptions: ChatOptions
    ) {
        var totalToolCalls = 0
        val totalToolCallsCounter = AtomicInteger(0)
        var llmCallIndex = 0
        var activeTools: List<Any> =
            if (maxToolCalls > 0) initialTools else emptyList()
        var chatOptions: ChatOptions = initialChatOptions
        var totalTokenUsage: TokenUsage? = null
        var totalLlmDurationMs = 0L
        var totalToolDurationMs = 0L
        var hadToolError = false
        var textRetryCount = 0
        /** 이전 iteration에서 성공한 도구 서명(이름+인자) 집합 — 중복 호출 감지용 */
        val succeededToolSignatures = mutableSetOf<String>()
        /** 성공한 도구 서명 → 결과 본문 매핑 — 사전 차단 시 캐시된 결과 재사용용 */
        val succeededToolResults = mutableMapOf<String, String>()
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
    ): Boolean = ReActLoopUtils.trackBudgetAndCheckExhausted(
        chatResponse?.metadata, tracker, "llm-call-$llmCallIndex", hookContext
    )

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
                runInterruptible(Dispatchers.IO) {
                    requestSpec.call().chatResponse()
                }
            }
        } finally {
            llmSpan.close()
        }
    }

    // ── private 메서드: 토큰 사용량 기록 ──

    /** LLM 응답의 토큰 사용량을 메트릭 콜백으로 전달한다. */
    private fun emitTokenUsageMetric(
        chatResponse: ChatResponse?,
        hookContext: HookContext
    ) = ReActLoopUtils.emitTokenUsageMetric(
        chatResponse?.metadata, hookContext, recordTokenUsage
    )

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
                    "tool.call.index" to
                        (currentTotalToolCalls + idx).toString()
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
            logger.error(e) { "도구 실행 실패, 메시지 쌍 건너뜀" }
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
    ): Boolean = ReActLoopUtils.shouldRetryAfterToolError(
        hadToolError, pendingToolCalls, activeTools, messages, textRetryCount
    )

    /** 루프 종료 시 LLM/Tool 소요 시간을 HookContext에 기록한다. */
    private fun recordLoopDurations(
        hookContext: HookContext,
        totalLlmDurationMs: Long,
        totalToolDurationMs: Long
    ) = ReActLoopUtils.recordLoopDurations(
        hookContext, totalLlmDurationMs, totalToolDurationMs
    )

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

    /**
     * LLM이 tool_calls를 구조적으로 생성하지 않고 텍스트로만 도구 호출 의도를 표현했는지 감지한다.
     * 예: "confluence_search_by_text(keyword='온보딩')" 또는 "잠시만 기다려 주세요" 패턴.
     */
    private fun looksLikeUnexecutedToolIntent(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return UNEXECUTED_TOOL_INTENT_PATTERN.containsMatchIn(text)
    }

    companion object {
        internal const val BUDGET_EXHAUSTED_MESSAGE =
            "토큰 예산이 초과되었습니다. 응답이 불완전할 수 있습니다."

        /** 같은 iteration 내 중복 호출 임시 마커 — mergeToolResponsesInOrder가 실제 결과로 치환한다. */
        private const val SAME_ITER_DUPLICATE_MARKER = "__arc_same_iter_dup__::"

        /** LLM이 도구를 호출하지 않고 텍스트로 도구 호출 코드를 작성한 패턴 */
        private val UNEXECUTED_TOOL_INTENT_PATTERN = Regex(
            "(?:" +
                "confluence_search|jira_search|bitbucket_|spec_|work_" +
                ")\\w*\\s*\\(" +
                "|" +
                "잠시만.*기다려.*주세요" +
                "|" +
                "찾아볼게요" +
                "|" +
                "검색해.*볼게요" +
                "|" +
                "BEGIN TOOL CALL",
            RegexOption.IGNORE_CASE
        )
    }
}
