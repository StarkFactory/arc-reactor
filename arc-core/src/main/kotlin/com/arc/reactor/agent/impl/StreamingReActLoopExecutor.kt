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
        val toolResponses = executeAndRecordTools(
            pendingToolCalls, state, hookContext,
            toolsUsed, maxToolCalls, allowedTools, emit
        )
        appendToolResponseMessage(messages, toolResponses)
        applyPostToolHints(toolResponses, state, messages, maxToolCalls)
        enforceMaxToolCalls(state, maxToolCalls, command, messages)
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
     */
    private suspend fun collectStreamChunks(
        requestSpec: ChatClient.ChatClientRequestSpec,
        emit: suspend (String) -> Unit,
        collectedContent: StringBuilder,
        currentIterationContent: StringBuilder
    ): StreamChunkResult {
        val flux = callWithRetry { requestSpec.stream().chatResponse() }
        var pendingToolCalls: List<AssistantMessage.ToolCall> = emptyList()
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
                pendingToolCalls = chunkToolCalls
            }
            val chunkUsage = chunk.metadata.usage
            if (chunkUsage != null && chunkUsage.totalTokens > 0) {
                lastChunkMeta = chunk.metadata
            }
        }

        return StreamChunkResult(
            pendingToolCalls = pendingToolCalls,
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
    }
}
