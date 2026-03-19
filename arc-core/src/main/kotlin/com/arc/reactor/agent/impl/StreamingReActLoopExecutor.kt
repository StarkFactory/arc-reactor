package com.arc.reactor.agent.impl

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
import reactor.util.retry.Retry
import java.time.Duration
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

    private val streamingRetry: Retry by lazy { buildStreamingRetry() }

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
        emit: suspend (String) -> Unit
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
            val currentIterationContent = StringBuilder()
            messageTrimmer.trim(
                messages, systemPrompt,
                activeTools.size * ReActLoopUtils.TOKENS_PER_TOOL_DEFINITION
            )

            val requestSpec = buildRequestSpec(
                activeChatClient, systemPrompt, messages, chatOptions, activeTools
            )
            val llmStart = System.nanoTime()

            // 스트리밍 LLM 호출 + 청크 수집
            val streamResult = collectStreamChunks(
                requestSpec, emit, collectedContent, currentIterationContent
            )
            totalLlmDurationMs += (System.nanoTime() - llmStart) / 1_000_000

            emitTokenUsageMetric(streamResult.lastChunkMeta, hookContext)

            lastIterationContent = currentIterationContent.toString()
            if (streamResult.pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
                if (shouldRetryAfterToolError(
                        hadToolError, streamResult.pendingToolCalls, activeTools,
                        messages, textRetryCount
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

            // AssistantMessage 조립 후 메시지에 추가
            val assistantMsg = AssistantMessage.builder()
                .content(streamResult.chunkText)
                .toolCalls(streamResult.pendingToolCalls)
                .build()
            messages.add(assistantMsg)

            // 도구 시작 이벤트 전송
            for (toolCall in streamResult.pendingToolCalls) {
                emit(StreamEventMarker.toolStart(toolCall.name()))
            }

            // Tool 병렬 실행
            val totalToolCallsCounter = AtomicInteger(totalToolCalls)
            val toolStart = System.nanoTime()
            val toolResponses = toolCallOrchestrator.executeInParallel(
                streamResult.pendingToolCalls, activeTools, hookContext,
                toolsUsed, totalToolCallsCounter, maxToolCalls, allowedTools,
                normalizeToolResponseToJson =
                    ReActLoopUtils.shouldNormalizeToolResponses(chatOptions)
            )
            totalToolDurationMs += (System.nanoTime() - toolStart) / 1_000_000
            totalToolCalls = totalToolCallsCounter.get()

            // 도구 종료 이벤트 전송
            for (toolCall in streamResult.pendingToolCalls) {
                emit(StreamEventMarker.toolEnd(toolCall.name()))
            }

            // ToolResponseMessage 추가 (메시지 쌍 무결성: 위에서 assistantMsg 이미 추가됨)
            messages.add(
                ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .build()
            )

            // 도구 에러 시 재시도 힌트 주입
            hadToolError = ReActLoopUtils.hasToolError(toolResponses)
            if (totalToolCalls < maxToolCalls) {
                ReActLoopUtils.injectToolErrorRetryHint(toolResponses, messages)
            }

            // maxToolCalls 도달 시 Tool 비활성화 — 무한 루프 방지 필수
            if (totalToolCalls >= maxToolCalls) {
                logger.info {
                    "maxToolCalls reached in streaming " +
                        "($totalToolCalls/$maxToolCalls), final answer"
                }
                activeTools = emptyList()
                chatOptions = buildChatOptions(command, false)
                messages.add(
                    ReActLoopUtils.buildMaxToolCallsMessage(
                        totalToolCalls, maxToolCalls
                    )
                )
            }
        }
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

        flux.retryWhen(streamingRetry).asFlow().collect { chunk ->
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
    ) {
        if (meta == null) return
        val usage = meta.usage ?: return
        val enrichedMetadata = buildMap<String, Any> {
            putAll(hookContext.metadata)
            put("runId", hookContext.runId)
            meta.model?.let { put("model", it) }
        }
        recordTokenUsage(
            TokenUsage(
                promptTokens = usage.promptTokens.toInt(),
                completionTokens = usage.completionTokens.toInt(),
                totalTokens = usage.totalTokens.toInt()
            ),
            enrichedMetadata
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

    private fun buildStreamingRetry(): Retry {
        val maxAttempts =
            retryProperties.maxAttempts.coerceAtLeast(1).toLong() - 1
        if (maxAttempts <= 0) return Retry.max(0)
        return Retry
            .backoff(
                maxAttempts,
                Duration.ofMillis(retryProperties.initialDelayMs)
            )
            .maxBackoff(Duration.ofMillis(retryProperties.maxDelayMs))
            .jitter(0.25)
            .filter { throwable ->
                isTransientError(unwrapReactorException(throwable))
            }
            .doBeforeRetry { signal ->
                logger.warn {
                    "Transient streaming error " +
                        "(attempt ${signal.totalRetries() + 1}), " +
                        "retrying: ${signal.failure().message}"
                }
            }
    }
}
