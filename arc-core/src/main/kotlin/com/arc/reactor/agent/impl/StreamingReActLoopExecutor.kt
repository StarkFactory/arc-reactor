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
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
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

        val messages = mutableListOf<Message>()
        if (conversationHistory.isNotEmpty()) {
            messages.addAll(conversationHistory)
        }
        messages.add(MediaConverter.buildUserMessage(command.userPrompt, command.media))

        while (true) {
            val currentIterationContent = StringBuilder()
            messageTrimmer.trim(messages, systemPrompt, activeTools.size * ReActLoopUtils.TOKENS_PER_TOOL_DEFINITION)

            val requestSpec = buildRequestSpec(activeChatClient, systemPrompt, messages, chatOptions, activeTools)
            val llmStart = System.nanoTime()
            val flux = callWithRetry { requestSpec.stream().chatResponse() }
            var pendingToolCalls: List<AssistantMessage.ToolCall> = emptyList()
            val currentChunkText = StringBuilder()
            var lastChunkMeta: org.springframework.ai.chat.metadata.ChatResponseMetadata? = null

            flux.retryWhen(streamingRetry).asFlow().collect { chunk ->
                val generation = chunk.result ?: return@collect
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
            totalLlmDurationMs += (System.nanoTime() - llmStart) / 1_000_000

            lastChunkMeta?.let { meta ->
                meta.usage?.let { usage ->
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
            }

            lastIterationContent = currentIterationContent.toString()
            if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
                hookContext.metadata["llmDurationMs"] = totalLlmDurationMs
                hookContext.metadata["toolDurationMs"] = totalToolDurationMs
                recordStageTiming(hookContext, "llm_calls", totalLlmDurationMs)
                recordStageTiming(hookContext, "tool_execution", totalToolDurationMs)
                agentMetrics.recordStageLatency("llm_calls", totalLlmDurationMs, command.metadata)
                agentMetrics.recordStageLatency("tool_execution", totalToolDurationMs, command.metadata)
                return StreamingLoopResult(
                    success = true,
                    collectedContent = collectedContent.toString(),
                    lastIterationContent = lastIterationContent
                )
            }

            val assistantMsg = AssistantMessage.builder()
                .content(currentChunkText.toString())
                .toolCalls(pendingToolCalls)
                .build()
            messages.add(assistantMsg)

            for (toolCall in pendingToolCalls) {
                emit(StreamEventMarker.toolStart(toolCall.name()))
            }

            val totalToolCallsCounter = AtomicInteger(totalToolCalls)
            val toolStart = System.nanoTime()
            val toolResponses = toolCallOrchestrator.executeInParallel(
                pendingToolCalls,
                activeTools,
                hookContext,
                toolsUsed,
                totalToolCallsCounter,
                maxToolCalls,
                allowedTools,
                normalizeToolResponseToJson = shouldNormalizeToolResponses(chatOptions)
            )
            totalToolDurationMs += (System.nanoTime() - toolStart) / 1_000_000
            totalToolCalls = totalToolCallsCounter.get()

            for (toolCall in pendingToolCalls) {
                emit(StreamEventMarker.toolEnd(toolCall.name()))
            }

            messages.add(
                ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .build()
            )

            // 도구 에러 시 재시도 힌트 주입 — LLM이 텍스트 대신 tool_call을 생성하도록 유도
            if (totalToolCalls < maxToolCalls) {
                ReActLoopUtils.injectToolErrorRetryHint(toolResponses, messages)
            }

            if (totalToolCalls >= maxToolCalls) {
                logger.info { "maxToolCalls reached in streaming ($totalToolCalls/$maxToolCalls), final answer" }
                activeTools = emptyList()
                chatOptions = buildChatOptions(command, false)
                messages.add(
                    org.springframework.ai.chat.messages.SystemMessage(
                        "Tool call limit reached ($totalToolCalls/$maxToolCalls). " +
                            "Summarize the results you have so far and provide your best answer. " +
                            "Do not request additional tool calls."
                    )
                )
            }
        }
    }

    private fun buildStreamingRetry(): Retry {
        val maxAttempts = retryProperties.maxAttempts.coerceAtLeast(1).toLong() - 1
        if (maxAttempts <= 0) return Retry.max(0)
        return Retry
            .backoff(maxAttempts, Duration.ofMillis(retryProperties.initialDelayMs))
            .maxBackoff(Duration.ofMillis(retryProperties.maxDelayMs))
            .jitter(0.25)
            .filter { throwable -> isTransientError(unwrapReactorException(throwable)) }
            .doBeforeRetry { signal ->
                logger.warn {
                    "Transient streaming error (attempt ${signal.totalRetries() + 1}), " +
                        "retrying: ${signal.failure().message}"
                }
            }
    }

    private fun shouldNormalizeToolResponses(chatOptions: ChatOptions): Boolean {
        return chatOptions is GoogleGenAiChatOptions
    }
}
