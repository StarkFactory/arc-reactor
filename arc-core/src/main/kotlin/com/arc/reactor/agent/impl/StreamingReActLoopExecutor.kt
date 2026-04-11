package com.arc.reactor.agent.impl

import com.arc.reactor.agent.budget.StepBudgetTracker
import com.arc.reactor.agent.config.RetryProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.hook.model.HookContext
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.prompt.ChatOptions
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.reactive.asFlow

private val logger = KotlinLogging.logger {}

/**
 * Reactor가 래핑한 예외를 언래핑하여 [AgentErrorPolicy]의 정확한 에러 분류를 위해
 * 원본 원인을 노출한다.
 *
 * Reactor의 `Exceptions.propagate()`는 체크 예외를 RuntimeException으로 래핑한다.
 * 이 함수는 원본 원인(cause)을 반환한다.
 */
internal fun unwrapReactorException(throwable: Throwable): Throwable {
    val cause = throwable.cause
    if (throwable is RuntimeException && cause != null && cause !== throwable) {
        return cause
    }
    return throwable
}

internal data class StreamingLoopResult(
    val success: Boolean,
    val collectedContent: String,
    val lastIterationContent: String
)

internal class StreamingReActLoopExecutor(
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
        suspend () -> Flux<org.springframework.ai.chat.model.ChatResponse>
    ) -> Flux<org.springframework.ai.chat.model.ChatResponse>,
    private val buildChatOptions: (AgentCommand, Boolean) -> ChatOptions,
    private val recordTokenUsage: (TokenUsage, Map<String, Any>) -> Unit = { _, _ -> },
    private val agentMetrics: AgentMetrics = NoOpAgentMetrics(),
    private val retryProperties: RetryProperties = RetryProperties(),
    private val isTransientError: (Throwable) -> Boolean = { false }
) {

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
        emit: suspend (String) -> Unit,
        budgetTracker: StepBudgetTracker? = null
    ): StreamingLoopResult {
        val state = initLoopState(command, maxToolCalls, initialTools)
        val messages = buildInitialMessages(conversationHistory, command)

        while (true) {
            val processResult = streamAndAccumulate(
                activeChatClient, systemPrompt, messages,
                state, emit, hookContext
            )
            if (checkBudgetExhausted(processResult, budgetTracker, state, hookContext, emit, command)) {
                return buildResult(false, state)
            }
            val action = resolveLoopAction(processResult, state, messages)
            when (action) {
                StreamLoopAction.FINISH -> {
                    recordLoopDurations(hookContext, state.totalLlmDurationMs, state.totalToolDurationMs, command)
                    return buildResult(true, state)
                }
                StreamLoopAction.RETRY -> continue
                StreamLoopAction.EXECUTE_TOOLS -> processToolCalls(
                    processResult, state, messages, hookContext,
                    toolsUsed, maxToolCalls, allowedTools, emit, command
                )
            }
        }
    }

    // ── 루프 상태 컨테이너 ──

    /**
     * 스트리밍 ReAct 루프의 가변 상태를 보관하는 내부 컨테이너.
     * 루프 반복마다 읽기/쓰기되는 변수를 하나로 묶어 메서드 간 전달을 단순화한다.
     */
    private class StreamingLoopState(
        initialTools: List<Any>,
        maxToolCalls: Int,
        initialChatOptions: ChatOptions
    ) {
        var activeTools: List<Any> =
            if (maxToolCalls > 0) initialTools else emptyList()
        var chatOptions: ChatOptions = initialChatOptions
        var totalToolCalls = 0
        val totalToolCallsCounter = AtomicInteger(0)
        var lastIterationContent = ""
        val collectedContent = StringBuilder()
        var totalLlmDurationMs = 0L
        var totalToolDurationMs = 0L
        var hadToolError = false
        var textRetryCount = 0
        /** 이전 iteration에서 성공한 도구 서명(이름+인자) 집합 — 중복 호출 감지용 */
        val succeededToolSignatures = mutableSetOf<String>()
        /** 성공한 도구 서명 → 결과 본문 매핑 — 사전 차단 시 캐시된 결과 재사용용 */
        val succeededToolResults = mutableMapOf<String, String>()
    }

    /** 루프 반복의 분기 결정을 표현하는 열거형. */
    private enum class StreamLoopAction { FINISH, RETRY, EXECUTE_TOOLS }

    // ── 루프 초기화 ──

    /** 루프 상태를 초기화한다. */
    private fun initLoopState(
        command: AgentCommand,
        maxToolCalls: Int,
        initialTools: List<Any>
    ): StreamingLoopState = StreamingLoopState(
        initialTools, maxToolCalls,
        buildChatOptions(command, maxToolCalls > 0 && initialTools.isNotEmpty())
    )

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

    // ── 단계 A: 스트리밍 LLM 호출 + 누적 ──

    /** 스트리밍 LLM을 호출하고 결과를 상태에 누적한다. */
    private suspend fun streamAndAccumulate(
        activeChatClient: ChatClient,
        systemPrompt: String,
        messages: MutableList<Message>,
        state: StreamingLoopState,
        emit: suspend (String) -> Unit,
        hookContext: HookContext
    ): StreamProcessResult {
        val result = collectAndProcessStream(
            activeChatClient, systemPrompt, messages,
            state.chatOptions, state.activeTools,
            emit, state.collectedContent, hookContext
        )
        state.totalLlmDurationMs += result.llmDurationMs
        state.lastIterationContent = result.iterationContent
        return result
    }

    // ── 단계 A-2: 예산 소진 확인 ──

    /** 토큰 예산 소진 여부를 확인한다. 소진 시 true를 반환한다. */
    private suspend fun checkBudgetExhausted(
        processResult: StreamProcessResult,
        budgetTracker: StepBudgetTracker?,
        state: StreamingLoopState,
        hookContext: HookContext,
        emit: suspend (String) -> Unit,
        command: AgentCommand
    ): Boolean {
        if (!trackBudgetAndCheckExhausted(
                processResult.streamResult.lastChunkMeta,
                budgetTracker, hookContext, emit
            )
        ) return false
        recordLoopDurations(
            hookContext, state.totalLlmDurationMs,
            state.totalToolDurationMs, command
        )
        return true
    }

    // ── 단계 B: 도구 호출 분기 판정 ──

    /** LLM 응답을 분석하여 루프 제어 액션을 결정한다. */
    private fun resolveLoopAction(
        processResult: StreamProcessResult,
        state: StreamingLoopState,
        messages: MutableList<Message>
    ): StreamLoopAction {
        val noPendingTools =
            processResult.streamResult.pendingToolCalls.isEmpty() ||
                state.activeTools.isEmpty()
        if (noPendingTools) {
            if (shouldRetryAfterToolError(
                    state.hadToolError,
                    processResult.streamResult.pendingToolCalls,
                    state.activeTools, messages, state.textRetryCount
                )
            ) {
                state.textRetryCount++
                state.hadToolError = false
                return StreamLoopAction.RETRY
            }
            return StreamLoopAction.FINISH
        }
        state.textRetryCount = 0
        return StreamLoopAction.EXECUTE_TOOLS
    }

    // ── 단계 C-G: 도구 실행 + 후처리 ──

    /** 도구를 실행하고 메시지 쌍 추가, 에러 힌트, maxToolCalls 검사를 수행한다. */
    private suspend fun processToolCalls(
        processResult: StreamProcessResult,
        state: StreamingLoopState,
        messages: MutableList<Message>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        maxToolCalls: Int,
        allowedTools: Set<String>?,
        emit: suspend (String) -> Unit,
        command: AgentCommand
    ) {
        val pendingToolCalls = processResult.streamResult.pendingToolCalls
        appendAssistantMessage(messages, processResult)
        // 사전 중복 차단: 이미 같은 서명으로 성공한 호출은 실행하지 않고 캐시된 결과를 재사용한다.
        val (newToolCalls, cachedResponses) = splitDuplicateToolCalls(pendingToolCalls, state)
        val executedResponses = if (newToolCalls.isEmpty()) {
            emptyList()
        } else {
            executeAndRecordTools(
                newToolCalls, state, hookContext,
                toolsUsed, maxToolCalls, allowedTools, emit
            )
        }
        val toolResponses = mergeToolResponsesInOrder(pendingToolCalls, cachedResponses, executedResponses)
        appendToolResponseMessage(messages, toolResponses)
        // 성공한 도구 서명/결과 기록 + 사후 힌트 주입
        recordSucceededAndMaybeHint(pendingToolCalls, toolResponses, cachedResponses, state, messages)
        applyPostToolHints(toolResponses, state, messages, maxToolCalls)
        enforceMaxToolCalls(state, maxToolCalls, command, messages)
    }

    /**
     * 들어온 toolCalls를 "신규"와 "이미 성공한 서명으로 캐시된 결과"로 분리한다.
     * 1. 이전 iteration 캐시 재사용 2. 같은 iteration 내 중복도 첫 번째만 실행
     */
    private fun splitDuplicateToolCalls(
        pendingToolCalls: List<AssistantMessage.ToolCall>,
        state: StreamingLoopState
    ): Pair<List<AssistantMessage.ToolCall>, List<ToolResponseMessage.ToolResponse>> {
        if (pendingToolCalls.isEmpty()) return pendingToolCalls to emptyList()
        val newCalls = mutableListOf<AssistantMessage.ToolCall>()
        val cached = mutableListOf<ToolResponseMessage.ToolResponse>()
        val sigToFirstCallId = mutableMapOf<String, String>()
        for (tc in pendingToolCalls) {
            val sig = buildToolSignature(tc)
            val prevResult = state.succeededToolResults[sig]
            if (prevResult != null) {
                logger.info { "중복 도구 호출 사전 차단 스트리밍(prev): ${tc.name()}" }
                cached.add(ToolResponseMessage.ToolResponse(tc.id(), tc.name(), prevResult))
                continue
            }
            val firstId = sigToFirstCallId[sig]
            if (firstId != null) {
                logger.info { "중복 도구 호출 사전 차단 스트리밍(same-iter): ${tc.name()}" }
                cached.add(
                    ToolResponseMessage.ToolResponse(
                        tc.id(),
                        tc.name(),
                        STREAM_SAME_ITER_MARKER + firstId
                    )
                )
            } else {
                sigToFirstCallId[sig] = tc.id()
                newCalls.add(tc)
            }
        }
        return newCalls to cached
    }

    /** 캐시된 응답과 실제 실행 응답을 원본 toolCall 순서대로 병합 + same-iter 마커 치환. */
    private fun mergeToolResponsesInOrder(
        pendingToolCalls: List<AssistantMessage.ToolCall>,
        cachedResponses: List<ToolResponseMessage.ToolResponse>,
        executedResponses: List<ToolResponseMessage.ToolResponse>
    ): List<ToolResponseMessage.ToolResponse> {
        if (cachedResponses.isEmpty()) return executedResponses
        val executedById = executedResponses.associateBy { it.id() }
        val resolved = cachedResponses.map { r ->
            val body = r.responseData()
            if (body.startsWith(STREAM_SAME_ITER_MARKER)) {
                val firstId = body.removePrefix(STREAM_SAME_ITER_MARKER)
                val sourceBody = executedById[firstId]?.responseData() ?: body
                ToolResponseMessage.ToolResponse(r.id(), r.name(), sourceBody)
            } else {
                r
            }
        }
        val byId = (resolved + executedResponses).associateBy { it.id() }
        // R320 fix: mapNotNull은 id 매칭 실패를 silent drop하여 AssistantMessage.toolCalls와
        // ToolResponseMessage.responses 수가 불일치할 수 있다. 이는 CLAUDE.md Gotcha #4
        // (메시지 쌍 무결성) 위반이며 이후 LLM 호출이 API 에러 또는 undefined behavior로 이어진다.
        // map + 누락 감지로 전환 — id가 누락되면 placeholder 에러 응답을 주입하여 쌍 무결성 유지.
        val missing = mutableListOf<String>()
        val result = pendingToolCalls.map { tc ->
            byId[tc.id()] ?: run {
                missing += tc.id()
                ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "Error: TOOL_RESPONSE_MISSING")
            }
        }
        if (missing.isNotEmpty()) {
            logger.warn {
                "mergeToolResponsesInOrder: ${missing.size}개 toolCall 응답 누락 — " +
                    "placeholder 에러 응답으로 쌍 무결성 보존 (ids=${missing.take(3)})"
            }
        }
        return result
    }

    /**
     * 새로 실행된 성공 도구의 서명/결과를 기록하고,
     * 캐시 재사용이 발생했거나 원본 toolCalls에서 사후 중복이 감지되면 힌트를 주입한다.
     */
    private fun recordSucceededAndMaybeHint(
        toolCalls: List<AssistantMessage.ToolCall>,
        allResponses: List<ToolResponseMessage.ToolResponse>,
        cachedResponses: List<ToolResponseMessage.ToolResponse>,
        state: StreamingLoopState,
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
        if (cachedResponses.isNotEmpty() || duplicateNames.isNotEmpty()) {
            val names = (cachedResponses.map { it.name() } + duplicateNames)
                .distinct()
                .joinToString(", ")
            logger.info { "동일 도구 중복 호출 감지 (스트리밍): $names — 종합 응답 유도 힌트 주입" }
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

    /** AssistantMessage를 조립하여 메시지에 추가한다 (메시지 쌍의 첫 번째). */
    private fun appendAssistantMessage(
        messages: MutableList<Message>,
        processResult: StreamProcessResult
    ) {
        val assistantMsg = AssistantMessage.builder()
            .content(processResult.streamResult.chunkText)
            .toolCalls(processResult.streamResult.pendingToolCalls)
            .build()
        messages.add(assistantMsg)
    }

    /** 도구를 병렬 실행하고 소요 시간을 상태에 누적한다. */
    private suspend fun executeAndRecordTools(
        pendingToolCalls: List<AssistantMessage.ToolCall>,
        state: StreamingLoopState,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        maxToolCalls: Int,
        allowedTools: Set<String>?,
        emit: suspend (String) -> Unit
    ): List<ToolResponseMessage.ToolResponse> {
        emitToolEvents(pendingToolCalls, emit, isStart = true)
        state.totalToolCallsCounter.set(state.totalToolCalls)
        val toolStart = System.nanoTime()
        val toolResponses = toolCallOrchestrator.executeInParallel(
            pendingToolCalls, state.activeTools, hookContext,
            toolsUsed, state.totalToolCallsCounter, maxToolCalls,
            allowedTools,
            normalizeToolResponseToJson =
                ReActLoopUtils.shouldNormalizeToolResponses(state.chatOptions)
        )
        state.totalToolDurationMs += (System.nanoTime() - toolStart) / 1_000_000
        state.totalToolCalls = state.totalToolCallsCounter.get()
        emitToolEvents(pendingToolCalls, emit, isStart = false)
        return toolResponses
    }

    /** ToolResponseMessage를 메시지에 추가한다 (메시지 쌍의 두 번째). */
    private fun appendToolResponseMessage(
        messages: MutableList<Message>,
        toolResponses: List<ToolResponseMessage.ToolResponse>
    ) {
        messages.add(
            ToolResponseMessage.builder()
                .responses(toolResponses)
                .build()
        )
    }

    /** 도구 에러 여부를 기록하고 재시도 힌트를 주입한다. */
    private fun applyPostToolHints(
        toolResponses: List<ToolResponseMessage.ToolResponse>,
        state: StreamingLoopState,
        messages: MutableList<Message>,
        maxToolCalls: Int
    ) {
        state.hadToolError = ReActLoopUtils.hasToolError(toolResponses)
        if (state.totalToolCalls < maxToolCalls) {
            ReActLoopUtils.injectToolErrorRetryHint(toolResponses, messages)
        }
    }

    /** maxToolCalls 도달 시 도구를 비활성화하고 최종 답변 요청 메시지를 주입한다. */
    private fun enforceMaxToolCalls(
        state: StreamingLoopState,
        maxToolCalls: Int,
        command: AgentCommand,
        messages: MutableList<Message>
    ) {
        val result = handleMaxToolCallsReached(
            state.totalToolCalls, maxToolCalls, command, messages
        ) ?: return
        state.activeTools = result.first
        state.chatOptions = result.second
    }

    /** 루프 결과를 상태에서 조립한다. */
    private fun buildResult(
        success: Boolean,
        state: StreamingLoopState
    ) = StreamingLoopResult(
        success = success,
        collectedContent = state.collectedContent.toString(),
        lastIterationContent = state.lastIterationContent
    )

    // ── private 메서드: 스트리밍 수집 + 처리 ──

    /** 스트리밍 수집 + 처리 결과를 담는 내부 데이터 클래스. */
    private data class StreamProcessResult(
        val streamResult: StreamChunkResult,
        val iterationContent: String,
        val llmDurationMs: Long
    )

    /**
     * 메시지 트리밍, LLM 스트리밍 호출, 청크 수집, 토큰 메트릭 기록을
     * 하나의 단계로 묶어 실행한다.
     */
    private suspend fun collectAndProcessStream(
        activeChatClient: ChatClient,
        systemPrompt: String,
        messages: MutableList<Message>,
        chatOptions: ChatOptions,
        activeTools: List<Any>,
        emit: suspend (String) -> Unit,
        collectedContent: StringBuilder,
        hookContext: HookContext
    ): StreamProcessResult {
        val currentIterationContent = StringBuilder()
        messageTrimmer.trim(
            messages, systemPrompt,
            activeTools.size * ReActLoopUtils.TOKENS_PER_TOOL_DEFINITION
        )
        val requestSpec = buildRequestSpec(
            activeChatClient, systemPrompt, messages, chatOptions, activeTools
        )
        val llmStart = System.nanoTime()
        val streamResult = collectStreamChunks(
            requestSpec, emit, collectedContent, currentIterationContent
        )
        val llmDurationMs = (System.nanoTime() - llmStart) / 1_000_000
        emitTokenUsageMetric(streamResult.lastChunkMeta, hookContext)
        return StreamProcessResult(
            streamResult = streamResult,
            iterationContent = currentIterationContent.toString(),
            llmDurationMs = llmDurationMs
        )
    }

    // ── private 메서드: 토큰 예산 추적 ──

    /**
     * 스트리밍 LLM 호출 후 토큰 예산을 추적한다.
     * EXHAUSTED이면 에러 이벤트를 emit하고 true를 반환한다.
     */
    private suspend fun trackBudgetAndCheckExhausted(
        meta: ChatResponseMetadata?,
        tracker: StepBudgetTracker?,
        hookContext: HookContext,
        emit: suspend (String) -> Unit
    ): Boolean {
        val exhausted = ReActLoopUtils.trackBudgetAndCheckExhausted(
            meta, tracker, "streaming-llm", hookContext
        )
        if (exhausted) {
            emit(StreamEventMarker.error(BUDGET_EXHAUSTED_MESSAGE))
        }
        return exhausted
    }

    // ── private 메서드: 도구 이벤트 SSE 전송 ──

    /**
     * 도구 시작 또는 종료 이벤트를 SSE로 전송한다.
     *
     * @param toolCalls 이벤트를 전송할 도구 호출 목록
     * @param emit SSE 전송 콜백
     * @param isStart true이면 시작 이벤트, false이면 종료 이벤트
     */
    private suspend fun emitToolEvents(
        toolCalls: List<AssistantMessage.ToolCall>,
        emit: suspend (String) -> Unit,
        isStart: Boolean
    ) {
        for (toolCall in toolCalls) {
            val event = if (isStart) {
                StreamEventMarker.toolStart(toolCall.name())
            } else {
                StreamEventMarker.toolEnd(toolCall.name())
            }
            emit(event)
        }
    }

    // ── private 메서드: maxToolCalls 도달 처리 ──

    /**
     * maxToolCalls 도달 시 도구를 비활성화하고 최종 답변 요청 메시지를 추가한다.
     *
     * @return 비활성화된 (activeTools, chatOptions) 쌍. 미도달 시 null
     */
    private fun handleMaxToolCallsReached(
        totalToolCalls: Int,
        maxToolCalls: Int,
        command: AgentCommand,
        messages: MutableList<Message>
    ): Pair<List<Any>, ChatOptions>? {
        if (totalToolCalls < maxToolCalls) return null
        logger.info {
            "maxToolCalls reached in streaming " +
                "($totalToolCalls/$maxToolCalls), final answer"
        }
        messages.add(
            ReActLoopUtils.buildMaxToolCallsMessage(totalToolCalls, maxToolCalls)
        )
        return emptyList<Any>() to buildChatOptions(command, false)
    }

    // ── private 메서드: 스트리밍 청크 수집 ──

    /** 스트리밍 청크 수집 결과를 담는 내부 데이터 클래스. */
    private data class StreamChunkResult(
        val pendingToolCalls: List<AssistantMessage.ToolCall>,
        val chunkText: String,
        val lastChunkMeta: ChatResponseMetadata?
    )

    /**
     * 스트리밍 LLM 응답의 청크를 수집하고,
     * 텍스트/도구호출/메타데이터를 분리 반환한다.
     *
     * R272 fix: 도구 호출은 ID별로 누적한다 (이전: 청크별 덮어쓰기).
     *
     * **이전 동작 (버그)**:
     * ```kotlin
     * if (!chunkToolCalls.isNullOrEmpty()) {
     *     pendingToolCalls = chunkToolCalls  // 마지막 청크만 보존
     * }
     * ```
     * 멀티 청크 도구 호출 스트리밍 시(예: OpenAI incremental tool call delta)
     * 첫 번째 청크의 도구 호출이 사라지고 마지막 청크만 남는다.
     * 현 Spring AI Gemini/Anthropic 구현은 단일 청크에 모든 도구 호출을 담아 보내므로
     * 숨어있던 P2 버그였으나, OpenAI Spring AI 구현 또는 미래 프로바이더 변경 시 즉시 발현.
     *
     * **R272 동작**:
     * ID별 LinkedHashMap에 누적하여 (1) 단일 청크 케이스 — map에 한 번에 채워지므로
     * 동일 동작, (2) 멀티 청크 full tool calls — 각 청크의 도구 호출이 ID로 중복 없이
     * 누적, (3) 동일 ID 재등장 — 최신 버전이 우선 (Spring AI는 청크별로 누적된 상태를
     * 전달하므로 latest-wins 정책이 안전).
     */
    private suspend fun collectStreamChunks(
        requestSpec: ChatClient.ChatClientRequestSpec,
        emit: suspend (String) -> Unit,
        collectedContent: StringBuilder,
        currentIterationContent: StringBuilder
    ): StreamChunkResult {
        val flux = callWithRetry { requestSpec.stream().chatResponse() }
        // R272: ID별 누적 (LinkedHashMap으로 삽입 순서 보존)
        val accumulatedToolCalls = LinkedHashMap<String, AssistantMessage.ToolCall>()
        val currentChunkText = StringBuilder()
        var lastChunkMeta: ChatResponseMetadata? = null

        flux.asFlow().collect { chunk ->
            val generation = chunk.result
            val text = generation.output.text
            if (!text.isNullOrEmpty()) {
                emit(text)
                currentChunkText.append(text)
                collectedContent.append(text)
                currentIterationContent.append(text)
            }
            val chunkToolCalls = generation.output.toolCalls
            if (!chunkToolCalls.isNullOrEmpty()) {
                // R272: 덮어쓰기 대신 ID별 누적. 동일 ID는 최신 청크의 ToolCall로 갱신.
                for (tc in chunkToolCalls) {
                    accumulatedToolCalls[tc.id()] = tc
                }
            }
            val chunkUsage = chunk.metadata.usage
            if (chunkUsage != null && chunkUsage.totalTokens > 0) {
                lastChunkMeta = chunk.metadata
            }
        }

        return StreamChunkResult(
            pendingToolCalls = accumulatedToolCalls.values.toList(),
            chunkText = currentChunkText.toString(),
            lastChunkMeta = lastChunkMeta
        )
    }

    // ── private 메서드: 토큰 사용량 기록 ──

    /** LLM 응답 메타데이터에서 토큰 사용량을 메트릭 콜백으로 전달한다. */
    private fun emitTokenUsageMetric(
        meta: ChatResponseMetadata?,
        hookContext: HookContext
    ) = ReActLoopUtils.emitTokenUsageMetric(
        meta, hookContext, recordTokenUsage
    )

    // ── private 메서드: 루프 종료 조건 ──

    /**
     * 도구 에러 직후 텍스트 응답이면 강화 힌트를 주입하고
     * 루프 재시도 여부를 반환한다.
     */
    private fun shouldRetryAfterToolError(
        hadToolError: Boolean,
        pendingToolCalls: List<AssistantMessage.ToolCall>,
        activeTools: List<Any>,
        messages: MutableList<Message>,
        textRetryCount: Int
    ): Boolean = ReActLoopUtils.shouldRetryAfterToolError(
        hadToolError, pendingToolCalls, activeTools, messages, textRetryCount
    )

    /**
     * 루프 종료 시 LLM/Tool 소요 시간을 HookContext와 AgentMetrics에 기록한다.
     */
    private fun recordLoopDurations(
        hookContext: HookContext,
        totalLlmDurationMs: Long,
        totalToolDurationMs: Long,
        command: AgentCommand
    ) = ReActLoopUtils.recordLoopDurations(
        hookContext, totalLlmDurationMs, totalToolDurationMs,
        agentMetrics, command.metadata
    )

    companion object {
        internal const val BUDGET_EXHAUSTED_MESSAGE =
            "토큰 예산이 초과되었습니다. 응답이 불완전할 수 있습니다."

        /** 스트리밍 모드 same-iteration 중복 마커 */
        private const val STREAM_SAME_ITER_MARKER = "__arc_stream_same_iter__::"
    }
}
