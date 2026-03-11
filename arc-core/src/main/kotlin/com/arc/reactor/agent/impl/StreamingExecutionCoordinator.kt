package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.hook.impl.UserMemoryInjectionHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message

private val logger = KotlinLogging.logger {}

/**
 * Extracted coordinator for streaming execution path.
 *
 * Keeps SpringAiAgentExecutor focused on orchestration while preserving the same runtime behavior.
 */
internal class StreamingExecutionCoordinator(
    private val concurrencySemaphore: Semaphore,
    private val requestTimeoutMs: Long,
    private val maxToolCallsLimit: Int,
    private val preExecutionResolver: PreExecutionResolver,
    private val conversationManager: ConversationManager,
    private val ragContextRetriever: RagContextRetriever,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val toolPreparationPlanner: ToolPreparationPlanner,
    private val resolveChatClient: (AgentCommand) -> ChatClient,
    private val resolveIntentAllowedTools: (AgentCommand) -> Set<String>?,
    private val streamingReActLoopExecutor: StreamingReActLoopExecutor,
    private val errorMessageResolver: ErrorMessageResolver,
    private val agentErrorPolicy: AgentErrorPolicy,
    private val agentMetrics: AgentMetrics
) {

    suspend fun execute(
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        emit: suspend (String) -> Unit
    ): StreamingExecutionState {
        val state = StreamingExecutionState()
        try {
            executeStreamingFlow(command, hookContext, toolsUsed, state, emit)
        } catch (e: BlockedIntentException) {
            handleBlockedIntent(e, state, emit)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            handleTimeout(e, state, emit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleUnexpectedFailure(e, state, emit)
        }
        return state
    }

    private suspend fun executeStreamingFlow(
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        state: StreamingExecutionState,
        emit: suspend (String) -> Unit
    ) {
        val queueStart = System.nanoTime()
        concurrencySemaphore.withPermit {
            val queueWaitMs = (System.nanoTime() - queueStart) / 1_000_000
            hookContext.metadata["queueWaitMs"] = queueWaitMs
            recordStageTiming(hookContext, "queue_wait", queueWaitMs)
            agentMetrics.recordStageLatency("queue_wait", queueWaitMs, command.metadata)
            executeWithRequestTimeout {
                val effectiveCommand = validateStreamingPreconditions(command, hookContext, state, emit)
                    ?: return@executeWithRequestTimeout
                state.streamStarted = true
                val setup = prepareLoopSetup(effectiveCommand, hookContext)
                val loopResult = runLoop(effectiveCommand, setup, hookContext, toolsUsed, emit)
                state.streamSuccess = loopResult.success
                state.collectedContent.append(loopResult.collectedContent)
                state.lastIterationContent = StringBuilder(loopResult.lastIterationContent)
            }
        }
    }

    private suspend fun executeWithRequestTimeout(block: suspend () -> Unit) {
        if (requestTimeoutMs <= 0L) {
            block()
            return
        }
        withTimeout(requestTimeoutMs) {
            block()
        }
    }

    private suspend fun validateStreamingPreconditions(
        command: AgentCommand,
        hookContext: HookContext,
        state: StreamingExecutionState,
        emit: suspend (String) -> Unit
    ): AgentCommand? {
        val guardStart = System.nanoTime()
        preExecutionResolver.checkGuard(command)?.let { rejection ->
            val guardDurationMs = (System.nanoTime() - guardStart) / 1_000_000
            hookContext.metadata["guardDurationMs"] = guardDurationMs
            recordStageTiming(hookContext, "guard", guardDurationMs)
            agentMetrics.recordStageLatency("guard", guardDurationMs, command.metadata)
            agentMetrics.recordGuardRejection(
                stage = rejection.stage ?: "unknown",
                reason = rejection.reason,
                metadata = command.metadata
            )
            state.streamErrorCode = AgentErrorCode.GUARD_REJECTED
            state.streamErrorMessage = rejection.reason
            emit(StreamEventMarker.error(rejection.reason))
            return null
        }
        val guardDurationMs = (System.nanoTime() - guardStart) / 1_000_000
        hookContext.metadata["guardDurationMs"] = guardDurationMs
        recordStageTiming(hookContext, "guard", guardDurationMs)
        agentMetrics.recordStageLatency("guard", guardDurationMs, command.metadata)

        val beforeHooksStartTime = System.nanoTime()
        preExecutionResolver.checkBeforeHooks(hookContext)?.let { rejection ->
            val beforeHooksDurationMs = (System.nanoTime() - beforeHooksStartTime) / 1_000_000
            recordStageTiming(hookContext, "before_hooks", beforeHooksDurationMs)
            agentMetrics.recordStageLatency("before_hooks", beforeHooksDurationMs, command.metadata)
            state.streamErrorCode = AgentErrorCode.HOOK_REJECTED
            state.streamErrorMessage = rejection.reason
            emit(StreamEventMarker.error(rejection.reason))
            return null
        }
        val beforeHooksDurationMs = (System.nanoTime() - beforeHooksStartTime) / 1_000_000
        recordStageTiming(hookContext, "before_hooks", beforeHooksDurationMs)
        agentMetrics.recordStageLatency("before_hooks", beforeHooksDurationMs, command.metadata)

        val effectiveCommand = preExecutionResolver.resolveIntent(command, hookContext)
        if (effectiveCommand.responseFormat != ResponseFormat.TEXT) {
            state.streamErrorCode = AgentErrorCode.INVALID_RESPONSE
            state.streamErrorMessage =
                "Structured ${effectiveCommand.responseFormat} output is not supported in streaming mode"
            emit(StreamEventMarker.error(state.streamErrorMessage.orEmpty()))
            return null
        }
        return effectiveCommand
    }

    private suspend fun prepareLoopSetup(command: AgentCommand, hookContext: HookContext): StreamingLoopSetup {
        val historyLoadStart = System.nanoTime()
        val conversationHistory = conversationManager.loadHistory(command)
        val historyLoadDurationMs = (System.nanoTime() - historyLoadStart) / 1_000_000
        recordStageTiming(hookContext, "history_load", historyLoadDurationMs)
        agentMetrics.recordStageLatency("history_load", historyLoadDurationMs, command.metadata)

        val ragStart = System.nanoTime()
        val ragContext = ragContextRetriever.retrieve(command)
        val ragDurationMs = (System.nanoTime() - ragStart) / 1_000_000
        recordStageTiming(hookContext, "rag_retrieval", ragDurationMs)
        agentMetrics.recordStageLatency("rag_retrieval", ragDurationMs, command.metadata)

        val userMemoryContext =
            hookContext.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY]?.toString()
        val baseSystemPrompt = if (userMemoryContext != null) {
            "${command.systemPrompt}\n\n[User Context]\n$userMemoryContext"
        } else {
            command.systemPrompt
        }
        val systemPrompt = systemPromptBuilder.build(
            baseSystemPrompt,
            ragContext,
            command.responseFormat,
            command.responseSchema,
            command.userPrompt
        )
        val effectiveMaxToolCalls = minOf(command.maxToolCalls, maxToolCallsLimit).coerceAtLeast(0)
        val toolSelectionStart = System.nanoTime()
        val selectedTools = if (command.mode == AgentMode.STANDARD || effectiveMaxToolCalls == 0) {
            emptyList()
        } else {
            toolPreparationPlanner.prepareForPrompt(command.userPrompt)
        }
        val toolSelectionDurationMs = (System.nanoTime() - toolSelectionStart) / 1_000_000
        recordStageTiming(hookContext, "tool_selection", toolSelectionDurationMs)
        agentMetrics.recordStageLatency("tool_selection", toolSelectionDurationMs, command.metadata)
        logger.debug { "Streaming ReAct: ${selectedTools.size} tools selected (mode=${command.mode})" }
        return StreamingLoopSetup(
            activeChatClient = resolveChatClient(command),
            systemPrompt = systemPrompt,
            initialTools = selectedTools,
            conversationHistory = conversationHistory,
            allowedTools = resolveIntentAllowedTools(command),
            maxToolCallLimit = effectiveMaxToolCalls
        )
    }

    private suspend fun runLoop(
        command: AgentCommand,
        setup: StreamingLoopSetup,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        emit: suspend (String) -> Unit
    ): StreamingLoopResult {
        val agentLoopStart = System.nanoTime()
        return streamingReActLoopExecutor.execute(
            command = command,
            activeChatClient = setup.activeChatClient,
            systemPrompt = setup.systemPrompt,
            initialTools = setup.initialTools,
            conversationHistory = setup.conversationHistory,
            hookContext = hookContext,
            toolsUsed = toolsUsed,
            allowedTools = setup.allowedTools,
            maxToolCalls = setup.maxToolCallLimit,
            emit = emit
        ).also { loopResult ->
            val agentLoopDurationMs = (System.nanoTime() - agentLoopStart) / 1_000_000
            recordStageTiming(hookContext, "agent_loop", agentLoopDurationMs)
            agentMetrics.recordStageLatency("agent_loop", agentLoopDurationMs, command.metadata)
            recordLoopStageLatency(hookContext, command.metadata, "llm_calls")
            recordLoopStageLatency(hookContext, command.metadata, "tool_execution")
        }
    }

    private suspend fun handleBlockedIntent(
        exception: BlockedIntentException,
        state: StreamingExecutionState,
        emit: suspend (String) -> Unit
    ) {
        logger.info { "Blocked intent in streaming: ${exception.intentName}" }
        state.streamErrorCode = AgentErrorCode.GUARD_REJECTED
        state.streamErrorMessage = exception.message
        emit(StreamEventMarker.error(state.streamErrorMessage.orEmpty()))
    }

    private suspend fun handleTimeout(
        exception: kotlinx.coroutines.TimeoutCancellationException,
        state: StreamingExecutionState,
        emit: suspend (String) -> Unit
    ) {
        logger.warn { "Streaming request timed out after ${requestTimeoutMs}ms" }
        state.streamErrorCode = AgentErrorCode.TIMEOUT
        state.streamErrorMessage = errorMessageResolver.resolve(AgentErrorCode.TIMEOUT, exception.message)
        emit(StreamEventMarker.error(state.streamErrorMessage.orEmpty()))
    }

    private suspend fun handleUnexpectedFailure(
        exception: Exception,
        state: StreamingExecutionState,
        emit: suspend (String) -> Unit
    ) {
        exception.throwIfCancellation()
        logger.error(exception) { "Streaming execution failed" }
        val errorCode = agentErrorPolicy.classify(exception)
        state.streamErrorCode = errorCode
        state.streamErrorMessage = errorMessageResolver.resolve(errorCode, exception.message)
        emit(StreamEventMarker.error(state.streamErrorMessage.orEmpty()))
    }

    private fun recordLoopStageLatency(hookContext: HookContext, metadata: Map<String, Any>, stage: String) {
        val durationMs = readStageTimings(hookContext)[stage] ?: return
        agentMetrics.recordStageLatency(stage, durationMs, metadata)
    }
}

internal data class StreamingExecutionState(
    var streamSuccess: Boolean = false,
    var streamErrorCode: AgentErrorCode? = null,
    var streamErrorMessage: String? = null,
    var streamStarted: Boolean = false,
    val collectedContent: StringBuilder = StringBuilder(),
    var lastIterationContent: StringBuilder = StringBuilder()
)

private data class StreamingLoopSetup(
    val activeChatClient: ChatClient,
    val systemPrompt: String,
    val initialTools: List<Any>,
    val conversationHistory: List<Message>,
    val allowedTools: Set<String>?,
    val maxToolCallLimit: Int
)
