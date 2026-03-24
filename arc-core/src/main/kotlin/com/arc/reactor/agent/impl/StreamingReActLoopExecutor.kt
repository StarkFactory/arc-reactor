package com.arc.reactor.agent.impl

import com.arc.reactor.agent.budget.BudgetStatus
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
        var activeTools = if (maxToolCalls > 0) initialTools else emptyList()
        var chatOptions = buildChatOptions(command, activeTools.isNotEmpty())
        var totalToolCalls = 0
        var lastIterationContent = ""
        val collectedContent = StringBuilder()
        var totalLlmDurationMs = 0L
        var totalToolDurationMs = 0L
        var hadToolError = false
        var textRetryCount = 0

        val messages = mutableListOf<Message>()
        if (conversationHistory.isNotEmpty()) {
            messages.addAll(conversationHistory)
        }
        messages.add(MediaConverter.buildUserMessage(command.userPrompt, command.media))

        while (true) {
            // 단계 A: 스트리밍 LLM 호출 + 청크 수집
            val processResult = collectAndProcessStream(
                activeChatClient, systemPrompt, messages, chatOptions,
                activeTools, emit, collectedContent, hookContext
            )
            totalLlmDurationMs += processResult.llmDurationMs
            lastIterationContent = processResult.iterationContent

            // 단계 A-2: 토큰 예산 추적 — EXHAUSTED 시 루프 종료
            if (trackBudgetAndCheckExhausted(
                    processResult.streamResult.lastChunkMeta,
                    budgetTracker, hookContext, emit
                )
            ) {
                recordLoopDurations(
                    hookContext, totalLlmDurationMs, totalToolDurationMs, command
                )
                return StreamingLoopResult(
                    success = false,
                    collectedContent = collectedContent.toString(),
                    lastIterationContent = lastIterationContent
                )
            }

            // 단계 B: 도구 호출 없으면 루프 종료 판단
            if (processResult.streamResult.pendingToolCalls.isEmpty() ||
                activeTools.isEmpty()
            ) {
                if (shouldRetryAfterToolError(
                        hadToolError,
                        processResult.streamResult.pendingToolCalls,
                        activeTools, messages, textRetryCount
                    )
                ) {
                    textRetryCount++
                    hadToolError = false
                    continue
                }
                recordLoopDurations(
                    hookContext, totalLlmDurationMs, totalToolDurationMs, command
                )
                return StreamingLoopResult(
                    success = true,
                    collectedContent = collectedContent.toString(),
                    lastIterationContent = lastIterationContent
                )
            }
            textRetryCount = 0

            // 단계 C: AssistantMessage 조립 후 메시지에 추가
            val pendingToolCalls = processResult.streamResult.pendingToolCalls
            val assistantMsg = AssistantMessage.builder()
                .content(processResult.streamResult.chunkText)
                .toolCalls(pendingToolCalls)
                .build()
            messages.add(assistantMsg)

            // 단계 D: 도구 이벤트 전송 + 병렬 실행
            emitToolEvents(pendingToolCalls, emit, isStart = true)
            val totalToolCallsCounter = AtomicInteger(totalToolCalls)
            val toolStart = System.nanoTime()
            val toolResponses = toolCallOrchestrator.executeInParallel(
                pendingToolCalls, activeTools, hookContext,
                toolsUsed, totalToolCallsCounter, maxToolCalls, allowedTools,
                normalizeToolResponseToJson =
                    ReActLoopUtils.shouldNormalizeToolResponses(chatOptions)
            )
            totalToolDurationMs += (System.nanoTime() - toolStart) / 1_000_000
            totalToolCalls = totalToolCallsCounter.get()
            emitToolEvents(pendingToolCalls, emit, isStart = false)

            // 단계 E: ToolResponseMessage 추가 (메시지 쌍 무결성)
            messages.add(
                ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .build()
            )

            // 단계 F: 도구 에러 시 재시도 힌트 주입
            hadToolError = ReActLoopUtils.hasToolError(toolResponses)
            if (totalToolCalls < maxToolCalls) {
                ReActLoopUtils.injectToolErrorRetryHint(toolResponses, messages)
            }

            // 단계 G: maxToolCalls 도달 시 Tool 비활성화
            val maxReachedResult = handleMaxToolCallsReached(
                totalToolCalls, maxToolCalls, command, messages
            )
            if (maxReachedResult != null) {
                activeTools = maxReachedResult.first
                chatOptions = maxReachedResult.second
            }
        }
    }

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
        tracker ?: return false
        val usage = meta?.usage ?: return false
        val status = tracker.trackStep(
            step = "streaming-llm",
            inputTokens = usage.promptTokens.toInt(),
            outputTokens = usage.completionTokens.toInt()
        )
        hookContext.metadata["tokensUsed"] = tracker.totalConsumed()
        hookContext.metadata["budgetStatus"] = status.name
        if (status == BudgetStatus.EXHAUSTED) {
            emit(StreamEventMarker.error(BUDGET_EXHAUSTED_MESSAGE))
            return true
        }
        return false
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

    /** LLM 응답 메타데이터에서 토큰 사용량을 메트릭 콜백으로 전달한다. 식별자만 포함하는 최소 메타데이터를 사용한다. */
    private fun emitTokenUsageMetric(
        meta: ChatResponseMetadata?,
        hookContext: HookContext
    ) {
        if (meta == null) return
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
    ): Boolean {
        if (!hadToolError || pendingToolCalls.isNotEmpty() || activeTools.isEmpty()) {
            return false
        }
        return ReActLoopUtils.injectForceRetryHintIfNeeded(messages, textRetryCount)
    }

    /**
     * 루프 종료 시 LLM/Tool 소요 시간을 HookContext와 AgentMetrics에 기록한다.
     */
    private fun recordLoopDurations(
        hookContext: HookContext,
        totalLlmDurationMs: Long,
        totalToolDurationMs: Long,
        command: AgentCommand
    ) {
        hookContext.metadata["llmDurationMs"] = totalLlmDurationMs
        hookContext.metadata["toolDurationMs"] = totalToolDurationMs
        recordStageTiming(hookContext, "llm_calls", totalLlmDurationMs)
        recordStageTiming(hookContext, "tool_execution", totalToolDurationMs)
        agentMetrics.recordStageLatency(
            "llm_calls", totalLlmDurationMs, command.metadata
        )
        agentMetrics.recordStageLatency(
            "tool_execution", totalToolDurationMs, command.metadata
        )
    }

    companion object {
        internal const val BUDGET_EXHAUSTED_MESSAGE =
            "토큰 예산이 초과되었습니다. 응답이 불완전할 수 있습니다."
    }
}
