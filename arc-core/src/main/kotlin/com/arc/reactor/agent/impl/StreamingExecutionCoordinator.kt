package com.arc.reactor.agent.impl

import com.arc.reactor.agent.budget.StepBudgetTracker
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
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message

private val logger = KotlinLogging.logger {}

/**
 * 스트리밍 실행 경로의 조율기 — SpringAiAgentExecutor에서 분리하여 책임을 명확히 한다.
 *
 * Guard 검증 -> 히스토리 로드 -> RAG 검색 -> 도구 준비 -> 스트리밍 ReAct 루프를 조율하며,
 * 동시성 세마포어와 요청 타임아웃을 적용한다. 런타임 동작은 분리 전과 동일하다.
 *
 * @see SpringAiAgentExecutor executeStream()에서 이 조율기에 스트리밍 실행을 위임
 * @see StreamingReActLoopExecutor 스트리밍 ReAct 루프 실행
 * @see StreamingFlowLifecycleCoordinator 스트리밍 완료 후 수명 주기 관리
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
    private val agentMetrics: AgentMetrics,
    private val createBudgetTracker: () -> StepBudgetTracker? = { null }
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
            hookContext.metadata[HookMetadataKeys.QUEUE_WAIT_MS] = queueWaitMs
            recordStageTiming(hookContext, "queue_wait", queueWaitMs)
            agentMetrics.recordStageLatency("queue_wait", queueWaitMs, command.metadata)
            executeWithRequestTimeout {
                val effectiveCommand = validateStreamingPreconditions(command, hookContext, state, emit)
                    ?: return@executeWithRequestTimeout
                state.streamStarted = true
                val setup = prepareLoopSetup(effectiveCommand, hookContext)
                val loopResult = runLoop(effectiveCommand, setup, hookContext, toolsUsed, emit)
                state.streamSuccess = loopResult.success
                if (!loopResult.success && hookContext.metadata["budgetStatus"] == "EXHAUSTED") {
                    state.streamErrorCode = AgentErrorCode.BUDGET_EXHAUSTED
                    state.streamErrorMessage = "토큰 예산이 소진되었습니다"
                }
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
        val guardRejection = measureStage("guard", hookContext, command.metadata) {
            preExecutionResolver.checkGuard(command)
        }
        hookContext.metadata[HookMetadataKeys.GUARD_DURATION_MS] =
            readStageTimings(hookContext)["guard"] ?: 0L
        if (guardRejection != null) {
            agentMetrics.recordGuardRejection(
                stage = guardRejection.stage ?: "unknown",
                reason = guardRejection.reason,
                metadata = command.metadata
            )
            state.streamErrorCode = AgentErrorCode.GUARD_REJECTED
            state.streamErrorMessage = guardRejection.reason
            emit(StreamEventMarker.error(guardRejection.reason))
            return null
        }

        val hookRejection = measureStage("before_hooks", hookContext, command.metadata) {
            preExecutionResolver.checkBeforeHooks(hookContext)
        }
        if (hookRejection != null) {
            state.streamErrorCode = AgentErrorCode.HOOK_REJECTED
            state.streamErrorMessage = hookRejection.reason
            emit(StreamEventMarker.error(hookRejection.reason))
            return null
        }

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
        val (conversationHistory, ragResult) = coroutineScope {
            val historyDeferred = async {
                measureStage("history_load", hookContext, command.metadata) {
                    conversationManager.loadHistory(command)
                }
            }
            val ragDeferred = async {
                measureStage("rag_retrieval", hookContext, command.metadata) {
                    val shouldRetrieveRag = RagRelevanceClassifier.isRagRequired(command)
                    if (shouldRetrieveRag) {
                        ragContextRetriever.retrieve(command)
                    } else {
                        logger.debug {
                            "RAG 검색 생략: 지식 질의가 아님"
                        }
                        null
                    }
                }
            }
            Pair(historyDeferred.await(), ragDeferred.await())
        }
        registerRagVerifiedSources(ragResult, hookContext)
        val ragContext = ragResult?.context

        val userMemoryContext =
            hookContext.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY]?.toString()
        val systemPrompt = systemPromptBuilder.build(
            command.systemPrompt,
            ragContext,
            command.responseFormat,
            command.responseSchema,
            command.userPrompt,
            userMemoryContext = userMemoryContext
        )
        val effectiveMaxToolCalls = minOf(command.maxToolCalls, maxToolCallsLimit).coerceAtLeast(0)
        val selectedTools = measureStage("tool_selection", hookContext, command.metadata) {
            if (command.mode == AgentMode.STANDARD || effectiveMaxToolCalls == 0) {
                emptyList()
            } else {
                toolPreparationPlanner.prepareForPrompt(command.userPrompt)
            }
        }
        logger.debug { "스트리밍 ReAct: 도구 ${selectedTools.size}개 선택 (mode=${command.mode})" }
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
        return measureStage("agent_loop", hookContext, command.metadata) {
            streamingReActLoopExecutor.execute(
                command = command,
                activeChatClient = setup.activeChatClient,
                systemPrompt = setup.systemPrompt,
                initialTools = setup.initialTools,
                conversationHistory = setup.conversationHistory,
                hookContext = hookContext,
                toolsUsed = toolsUsed,
                allowedTools = setup.allowedTools,
                maxToolCalls = setup.maxToolCallLimit,
                emit = emit,
                budgetTracker = createBudgetTracker()
            )
        }.also {
            recordLoopStageLatency(hookContext, command.metadata, "llm_calls", agentMetrics)
            recordLoopStageLatency(hookContext, command.metadata, "tool_execution", agentMetrics)
        }
    }

    private suspend fun handleBlockedIntent(
        exception: BlockedIntentException,
        state: StreamingExecutionState,
        emit: suspend (String) -> Unit
    ) {
        logger.info { "스트리밍에서 차단된 인텐트: ${exception.intentName}" }
        state.streamErrorCode = AgentErrorCode.GUARD_REJECTED
        // R277: SSE 스트림에 노출되는 메시지에서 exception 클래스명 제거.
        // R271(LLM 출력)/R274(audit 채널)에 이은 세 번째 노출 채널 보호. CLAUDE.md
        // Critical Gotcha #9 정신 — `BlockedIntentException` 같은 내부 클래스명이
        // SSE를 통해 클라이언트에 도달하면 내부 아키텍처 정보가 노출된다. 클래스명은
        // logger.info에만 기록(이미 위에서 처리)하고 사용자 메시지는 일반 텍스트 유지.
        state.streamErrorMessage = "요청이 보안 정책에 의해 차단되었습니다."
        emit(StreamEventMarker.error(state.streamErrorMessage.orEmpty()))
    }

    private suspend fun handleTimeout(
        exception: kotlinx.coroutines.TimeoutCancellationException,
        state: StreamingExecutionState,
        emit: suspend (String) -> Unit
    ) {
        logger.warn { "스트리밍 요청 타임아웃: ${requestTimeoutMs}ms 경과" }
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
        val unwrapped = unwrapReactorException(exception)
        val effectiveException = if (unwrapped is Exception) unwrapped else exception
        logger.error(effectiveException) { "스트리밍 실행 실패" }
        val errorCode = agentErrorPolicy.classify(effectiveException)
        state.streamErrorCode = errorCode
        state.streamErrorMessage = errorMessageResolver.resolve(
            errorCode, effectiveException.message
        )
        emit(StreamEventMarker.error(state.streamErrorMessage.orEmpty()))
    }


    /** nanoTime 측정 + recordStageTiming + recordStageLatency 반복 패턴을 통합하는 래퍼 */
    private suspend inline fun <T> measureStage(
        stage: String,
        hookContext: HookContext,
        metadata: Map<String, Any>,
        block: () -> T
    ): T {
        val start = System.nanoTime()
        return block().also {
            val durationMs = (System.nanoTime() - start) / 1_000_000
            recordStageTiming(hookContext, stage, durationMs)
            agentMetrics.recordStageLatency(stage, durationMs, metadata)
        }
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
