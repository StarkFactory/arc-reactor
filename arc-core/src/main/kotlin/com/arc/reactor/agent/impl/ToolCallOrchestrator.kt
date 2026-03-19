package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.ToolResultCacheProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.guard.tool.ToolOutputSanitizer
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.response.ToolResponseSignal
import com.arc.reactor.response.ToolResponseSignalExtractor
import com.arc.reactor.response.VerifiedSource
import com.arc.reactor.response.VerifiedSourceExtractor
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import org.springframework.aop.framework.Advised
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * 병렬 도구 실행 오케스트레이터 — Tool Call의 전체 라이프사이클을 관리합니다.
 *
 * 주요 책임:
 * - Tool Call 병렬 실행 ([executeInParallel]) 및 단건 직접 실행 ([executeDirectToolCall])
 * - BeforeToolCallHook / AfterToolCallHook 실행
 * - Human-in-the-Loop 승인 대기 ([ToolApprovalPolicy])
 * - Tool 출력 새니타이징 ([ToolOutputSanitizer])
 * - Tool 결과 캐시 (Caffeine 기반, 설정으로 활성화)
 * - maxToolCalls 제한 슬롯 예약 (CAS 기반 원자적 카운터)
 * - Tool 응답 신호(VerifiedSource, ToolResponseSignal) 추출 및 HookContext 병합
 *
 * @see ManualReActLoopExecutor 이 오케스트레이터를 호출하는 ReAct 루프
 * @see SpringAiAgentExecutor 이 오케스트레이터를 생성하는 상위 클래스
 * @see ArcToolCallbackAdapter Arc 프레임워크의 Tool 어댑터
 */
internal class ToolCallOrchestrator(
    private val toolCallTimeoutMs: Long,
    private val hookExecutor: HookExecutor?,
    private val toolApprovalPolicy: ToolApprovalPolicy?,
    private val pendingApprovalStore: PendingApprovalStore?,
    private val agentMetrics: AgentMetrics,
    private val parseToolArguments: (String?) -> Map<String, Any?> = ::parseToolArguments,
    private val toolOutputSanitizer: ToolOutputSanitizer? = null,
    private val maxToolOutputLength: Int = DEFAULT_MAX_TOOL_OUTPUT_LENGTH,
    private val requesterAwareToolNames: Set<String> = emptySet(),
    private val toolResultCacheProperties: ToolResultCacheProperties = ToolResultCacheProperties(),
    private val tokenEstimator: TokenEstimator? = null,
    private val maxContextWindowTokens: Int = DEFAULT_MAX_CONTEXT_WINDOW_TOKENS
) {
    /** Spring AI ToolCallback 해석 결과 캐시 — MethodToolCallbackProvider 호출 비용 절감. 크기 제한으로 메모리 누수 방지. */
    private val springToolCallbackCache: Cache<Int, Map<String, org.springframework.ai.tool.ToolCallback>> =
        Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(java.time.Duration.ofMinutes(10))
            .build()

    /** Tool 결과 캐시 (Caffeine) — 동일 입력에 대한 중복 Tool 호출 방지 */
    private val toolResultCache: Cache<String, String>? = buildToolResultCacheIfEnabled()


    // ──────────────────────────────────────────────
    // 공개 API: 단건 직접 실행 / 병렬 실행
    // ──────────────────────────────────────────────

    /**
     * 단건 도구를 직접 실행합니다. 강제 Workspace Tool 등에 사용됩니다.
     *
     * allowedTools 확인 -> BeforeToolCallHook -> 승인 검사 -> Tool 실행 ->
     * 출력 새니타이징 -> AfterToolCallHook -> 메트릭 기록의 전체 흐름을 처리합니다.
     *
     * @param toolName 실행할 Tool 이름
     * @param toolParams Tool에 전달할 파라미터
     * @param tools 등록된 Tool 목록
     * @param hookContext Hook/메트릭용 실행 컨텍스트
     * @param toolsUsed 실행된 도구 이름을 누적하는 리스트
     * @param allowedTools Intent 기반 Tool 허용 목록 (null이면 전체 허용)
     * @return Tool 실행 결과
     */
    suspend fun executeDirectToolCall(
        toolName: String,
        toolParams: Map<String, Any?>,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        allowedTools: Set<String>? = null
    ): ToolCallResult {
        if (allowedTools != null && toolName !in allowedTools) {
            return rejectByAllowlist(toolName, allowedTools.size)
        }

        val effectiveToolParams = enrichToolParamsForRequesterAwareTools(
            toolName = toolName,
            toolParams = toolParams,
            metadata = hookContext.metadata
        )
        val toolCallContext = buildToolCallContext(hookContext, toolName, effectiveToolParams, toolsUsed.size)

        checkBeforeToolCallHook(toolCallContext)?.let { rejection ->
            return rejectByHook(toolName, rejection.reason)
        }
        checkToolApproval(toolName, toolCallContext, hookContext)?.let { rejection ->
            publishBlockedToolCallResult(toolCallContext, toolName, rejection)
            return ToolCallResult(success = false, output = rejection, errorMessage = rejection, durationMs = 0)
        }

        return invokeAndFinalizeDirect(
            toolName = toolName,
            effectiveToolParams = effectiveToolParams,
            tools = tools,
            hookContext = hookContext,
            toolCallContext = toolCallContext,
            toolsUsed = toolsUsed
        )
    }

    /**
     * LLM이 요청한 Tool Call 목록을 코루틴으로 병렬 실행합니다.
     *
     * 각 Tool Call은 [executeSingleToolCall]로 위임되며, 모든 결과를 수집한 후
     * toolsUsed 누적과 ToolCapture(VerifiedSource, Signal) 병합을 수행합니다.
     *
     * @param toolCalls LLM이 요청한 Tool Call 목록
     * @param tools 등록된 Tool 목록
     * @param hookContext Hook/메트릭용 실행 컨텍스트
     * @param toolsUsed 실행된 도구 이름을 누적하는 리스트
     * @param totalToolCallsCounter 전체 Tool 호출 횟수 (원자적 카운터)
     * @param maxToolCalls 최대 Tool 호출 횟수
     * @param allowedTools Intent 기반 Tool 허용 목록 (null이면 전체 허용)
     * @param normalizeToolResponseToJson Google GenAI 등 JSON 응답 필수 프로바이더 여부
     * @return Tool 응답 메시지 목록 (ToolResponseMessage 조립용)
     */
    suspend fun executeInParallel(
        toolCalls: List<AssistantMessage.ToolCall>,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        totalToolCallsCounter: AtomicInteger,
        maxToolCalls: Int,
        allowedTools: Set<String>?,
        normalizeToolResponseToJson: Boolean = false
    ): List<ToolResponseMessage.ToolResponse> = coroutineScope {
        val springCallbacksByName = resolveSpringToolCallbacksByName(tools)
        val executions = toolCalls.map { toolCall ->
            async {
                executeSingleToolCall(
                    toolCall = toolCall,
                    tools = tools,
                    springCallbacksByName = springCallbacksByName,
                    hookContext = hookContext,
                    totalToolCallsCounter = totalToolCallsCounter,
                    maxToolCalls = maxToolCalls,
                    allowedTools = allowedTools,
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            }
        }.awaitAll()
        collectParallelResults(executions, hookContext, toolsUsed)
    }

    // ──────────────────────────────────────────────
    // 단일 Tool Call 실행 (병렬 실행의 개별 단위)
    // ──────────────────────────────────────────────

    /**
     * 단일 Tool Call을 실행합니다.
     *
     * 실행 순서: allowedTools 확인 -> Hook -> 승인 검사 -> Tool 존재 확인 ->
     * 슬롯 예약(CAS) -> Tool 실행 -> 출력 새니타이징 -> AfterToolCallHook -> 메트릭 기록
     */
    private suspend fun executeSingleToolCall(
        toolCall: AssistantMessage.ToolCall,
        tools: List<Any>,
        springCallbacksByName: Map<String, org.springframework.ai.tool.ToolCallback>,
        hookContext: HookContext,
        totalToolCallsCounter: AtomicInteger,
        maxToolCalls: Int,
        allowedTools: Set<String>?,
        normalizeToolResponseToJson: Boolean
    ): ParallelToolExecution {
        val toolName = toolCall.name()

        checkAllowlist(toolCall, toolName, allowedTools, normalizeToolResponseToJson)
            ?.let { return it }

        val parsedToolParams = parseToolArguments(toolCall.arguments())
        val effectiveToolParams = enrichToolParamsForRequesterAwareTools(
            toolName = toolName, toolParams = parsedToolParams, metadata = hookContext.metadata
        )
        val toolCallContext = buildToolCallContext(
            hookContext, toolName, effectiveToolParams, totalToolCallsCounter.get()
        )

        checkHookAndApproval(
            toolCall, toolName, toolCallContext, hookContext, normalizeToolResponseToJson
        )?.let { return it }

        checkToolExistsAndReserveSlot(
            toolCall, toolName, tools, springCallbacksByName,
            totalToolCallsCounter, maxToolCalls, normalizeToolResponseToJson
        )?.let { return it }

        return invokeAndFinalizeParallel(
            toolCall = toolCall,
            toolName = toolName,
            toolInput = serializeToolInput(effectiveToolParams, toolCall.arguments()),
            toolCallContext = toolCallContext,
            tools = tools,
            springCallbacksByName = springCallbacksByName,
            hookContext = hookContext,
            normalizeToolResponseToJson = normalizeToolResponseToJson
        )
    }

    /** allowedTools 허용 목록을 확인합니다. 차단 시 [ParallelToolExecution] 반환. */
    private fun checkAllowlist(
        toolCall: AssistantMessage.ToolCall,
        toolName: String,
        allowedTools: Set<String>?,
        normalizeToolResponseToJson: Boolean
    ): ParallelToolExecution? {
        if (allowedTools == null || toolName in allowedTools) return null
        val msg = toolNotAllowedMessage(toolName)
        logger.info { "Tool call blocked by allowlist: tool=$toolName allowedTools=${allowedTools.size}" }
        agentMetrics.recordToolCall(toolName, 0, false)
        return ParallelToolExecution(
            response = buildToolResponse(toolCall, toolName, msg, normalizeToolResponseToJson)
        )
    }

    /** BeforeToolCallHook과 승인 검사를 수행합니다. 거부 시 [ParallelToolExecution] 반환. */
    private suspend fun checkHookAndApproval(
        toolCall: AssistantMessage.ToolCall,
        toolName: String,
        toolCallContext: ToolCallContext,
        hookContext: HookContext,
        normalizeToolResponseToJson: Boolean
    ): ParallelToolExecution? {
        checkBeforeToolCallHook(toolCallContext)?.let { rejection ->
            logger.info { "Tool call $toolName rejected by hook: ${rejection.reason}" }
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall, toolName, "Tool call rejected: ${rejection.reason}", normalizeToolResponseToJson
                )
            )
        }
        checkToolApproval(toolName, toolCallContext, hookContext)?.let { rejection ->
            publishBlockedToolCallResult(toolCallContext, toolName, rejection)
            return ParallelToolExecution(
                response = buildToolResponse(toolCall, toolName, rejection, normalizeToolResponseToJson)
            )
        }
        return null
    }

    /** Tool 존재 확인과 실행 슬롯 예약을 수행합니다. 실패 시 [ParallelToolExecution] 반환. */
    private fun checkToolExistsAndReserveSlot(
        toolCall: AssistantMessage.ToolCall,
        toolName: String,
        tools: List<Any>,
        springCallbacksByName: Map<String, org.springframework.ai.tool.ToolCallback>,
        totalToolCallsCounter: AtomicInteger,
        maxToolCalls: Int,
        normalizeToolResponseToJson: Boolean
    ): ParallelToolExecution? {
        val toolExists = findToolAdapter(toolName, tools) != null ||
            springCallbacksByName.containsKey(toolName)
        if (!toolExists) {
            logger.warn { "Tool '$toolName' not found (possibly hallucinated by LLM)" }
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall, toolName, toolNotFoundMessage(toolName), normalizeToolResponseToJson
                )
            )
        }
        if (reserveToolExecutionSlot(totalToolCallsCounter, maxToolCalls) == null) {
            logger.warn { "maxToolCalls ($maxToolCalls) reached, stopping tool execution" }
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall, toolCall.name(),
                    "Error: Maximum tool call limit ($maxToolCalls) reached",
                    normalizeToolResponseToJson
                )
            )
        }
        return null
    }

    // ──────────────────────────────────────────────
    // Tool 실행 + 후처리 (직접 실행 / 병렬 실행)
    // ──────────────────────────────────────────────

    /** 직접 실행 경로: Tool 호출 -> 새니타이징 -> Hook -> 메트릭 기록 */
    private suspend fun invokeAndFinalizeDirect(
        toolName: String,
        effectiveToolParams: Map<String, Any?>,
        tools: List<Any>,
        hookContext: HookContext,
        toolCallContext: ToolCallContext,
        toolsUsed: MutableList<String>
    ): ToolCallResult {
        val toolStartTime = System.currentTimeMillis()
        val springCallbacksByName = resolveSpringToolCallbacksByName(tools)
        val toolInput = serializeToolInput(effectiveToolParams, null)
        val invocation = invokeToolAdapter(toolName, toolInput, tools, springCallbacksByName)

        if (invocation.trackAsUsed) toolsUsed.add(toolName)
        captureToolSignals(hookContext, toolName, invocation.output, invocation.success)

        val toolOutput = sanitizeOutput(toolName, invocation.output)
        estimateAndWarnToolOutputTokens(toolName, toolOutput, hookContext)
        val toolDurationMs = System.currentTimeMillis() - toolStartTime
        val result = ToolCallResult(
            success = invocation.success,
            output = toolOutput,
            errorMessage = if (!invocation.success) toolOutput else null,
            durationMs = toolDurationMs
        )
        hookExecutor?.executeAfterToolCall(toolCallContext, result)
        agentMetrics.recordToolCall(toolName, toolDurationMs, invocation.success)
        return result
    }

    /** 병렬 실행 경로: Tool 호출 -> 새니타이징 -> Hook -> 메트릭 기록 */
    private suspend fun invokeAndFinalizeParallel(
        toolCall: AssistantMessage.ToolCall,
        toolName: String,
        toolInput: String,
        toolCallContext: ToolCallContext,
        tools: List<Any>,
        springCallbacksByName: Map<String, org.springframework.ai.tool.ToolCallback>,
        hookContext: HookContext,
        normalizeToolResponseToJson: Boolean
    ): ParallelToolExecution {
        val toolStartTime = System.currentTimeMillis()
        val invocation = invokeToolAdapter(toolName, toolInput, tools, springCallbacksByName)
        val capture = extractToolCapture(toolName, invocation.output, invocation.success)
        val toolOutput = sanitizeOutput(toolName, invocation.output)
        estimateAndWarnToolOutputTokens(toolName, toolOutput, hookContext)
        val toolDurationMs = System.currentTimeMillis() - toolStartTime

        hookExecutor?.executeAfterToolCall(
            context = toolCallContext,
            result = ToolCallResult(
                success = invocation.success,
                output = toolOutput,
                errorMessage = if (!invocation.success) toolOutput else null,
                durationMs = toolDurationMs
            )
        )
        agentMetrics.recordToolCall(toolName, toolDurationMs, invocation.success)

        return ParallelToolExecution(
            response = buildToolResponse(toolCall, toolName, toolOutput, normalizeToolResponseToJson),
            usedToolName = toolName.takeIf { invocation.trackAsUsed },
            capture = capture
        )
    }

    // ──────────────────────────────────────────────
    // 거부 응답 빌더
    // ──────────────────────────────────────────────

    /** allowedTools에 의해 차단된 직접 호출 결과를 생성합니다. */
    private fun rejectByAllowlist(toolName: String, allowedToolsSize: Int): ToolCallResult {
        val message = toolNotAllowedMessage(toolName)
        logger.info { "Direct tool call blocked by allowlist: tool=$toolName allowedTools=$allowedToolsSize" }
        agentMetrics.recordToolCall(toolName, 0, false)
        return ToolCallResult(success = false, output = message, errorMessage = message, durationMs = 0)
    }

    /** Hook에 의해 거부된 직접 호출 결과를 생성합니다. */
    private fun rejectByHook(toolName: String, reason: String): ToolCallResult {
        val message = "Tool call rejected: $reason"
        logger.info { "Direct tool call $toolName rejected by hook: $reason" }
        return ToolCallResult(success = false, output = message, errorMessage = message, durationMs = 0)
    }

    // ──────────────────────────────────────────────
    // 슬롯 예약 / 컨텍스트 빌더 / 출력 새니타이징
    // ──────────────────────────────────────────────

    /**
     * CAS(Compare-And-Set) 기반 Tool 실행 슬롯 예약.
     *
     * 병렬 실행 시 maxToolCalls를 초과하지 않도록 원자적으로 카운터를 증가시킵니다.
     * @return 예약된 슬롯 인덱스, 초과 시 null
     */
    private fun reserveToolExecutionSlot(counter: AtomicInteger, maxToolCalls: Int): Int? {
        while (true) {
            val current = counter.get()
            if (current >= maxToolCalls) return null
            if (counter.compareAndSet(current, current + 1)) return current
        }
    }

    /** ToolCallContext를 생성합니다. */
    private fun buildToolCallContext(
        hookContext: HookContext,
        toolName: String,
        toolParams: Map<String, Any?>,
        callIndex: Int
    ): ToolCallContext {
        return ToolCallContext(
            agentContext = hookContext,
            toolName = toolName,
            toolParams = toolParams,
            callIndex = callIndex
        )
    }

    /** Tool 출력에 새니타이저를 적용합니다. 새니타이저 미설정 시 원본 반환. */
    private fun sanitizeOutput(toolName: String, output: String): String {
        if (toolOutputSanitizer == null) return output
        return toolOutputSanitizer.sanitize(toolName, output).content
    }

    // ──────────────────────────────────────────────
    // Tool 응답 신호 추출 / 병합
    // ──────────────────────────────────────────────

    /** Tool 출력에서 VerifiedSource와 ToolResponseSignal을 추출합니다. */
    private fun extractToolCapture(
        toolName: String,
        toolOutput: String,
        toolSuccess: Boolean
    ): ToolCapture {
        if (!toolSuccess) return ToolCapture()
        return ToolCapture(
            verifiedSources = VerifiedSourceExtractor.extract(toolName, toolOutput),
            signal = ToolResponseSignalExtractor.extract(toolName, toolOutput)
        )
    }

    /** 추출된 ToolCapture를 HookContext에 병합합니다 (중복 URL 제거). */
    private fun mergeToolCapture(hookContext: HookContext, capture: ToolCapture) {
        mergeVerifiedSources(hookContext, capture.verifiedSources)
        capture.signal?.let { mergeSignalMetadata(hookContext, it) }
    }

    /** 중복 URL을 제거하며 VerifiedSource를 HookContext에 추가합니다. HashSet으로 O(N) 탐색. */
    private fun mergeVerifiedSources(hookContext: HookContext, sources: List<VerifiedSource>) {
        val existingUrls = hookContext.verifiedSources.mapTo(HashSet()) { it.url }
        for (source in sources) {
            if (source.url !in existingUrls) {
                hookContext.verifiedSources.add(source)
                existingUrls.add(source.url)
            }
        }
    }

    /** ToolResponseSignal 메타데이터를 HookContext에 병합합니다. */
    private fun mergeSignalMetadata(hookContext: HookContext, signal: ToolResponseSignal) {
        val signals = getOrCreateToolSignals(hookContext)
        signals += signal
        signal.answerMode?.let { hookContext.metadata["answerMode"] = it }
        signal.grounded?.let { hookContext.metadata["grounded"] = it }
        signal.freshness?.let { hookContext.metadata["freshness"] = it }
        signal.retrievedAt?.let { hookContext.metadata["retrievedAt"] = it }
        signal.blockReason?.let { hookContext.metadata["blockReason"] = it }
    }

    private fun captureToolSignals(
        hookContext: HookContext,
        toolName: String,
        toolOutput: String,
        toolSuccess: Boolean
    ) {
        mergeToolCapture(hookContext, extractToolCapture(toolName, toolOutput, toolSuccess))
    }

    @Suppress("UNCHECKED_CAST")
    private fun getOrCreateToolSignals(hookContext: HookContext): MutableList<ToolResponseSignal> {
        return hookContext.metadata.getOrPut(TOOL_SIGNALS_METADATA_KEY) {
            mutableListOf<ToolResponseSignal>()
        } as MutableList<ToolResponseSignal>
    }

    // ──────────────────────────────────────────────
    // Hook / 승인 검사
    // ──────────────────────────────────────────────

    /** BeforeToolCallHook을 실행하여 Tool 호출 거부 여부를 확인합니다. */
    private suspend fun checkBeforeToolCallHook(context: ToolCallContext): HookResult.Reject? {
        if (hookExecutor == null) return null
        return hookExecutor.executeBeforeToolCall(context) as? HookResult.Reject
    }

    /**
     * Human-in-the-Loop: Tool 호출에 대한 사람 승인이 필요한지 확인하고 대기합니다.
     *
     * [ToolApprovalPolicy]로 승인 필요 여부를 판단하고, 필요하면 [PendingApprovalStore]에
     * 승인을 요청한 뒤 응답을 대기합니다.
     *
     * @return 거부 또는 타임아웃 시 거부 메시지, 승인 또는 정책 없음 시 null
     */
    private suspend fun checkToolApproval(
        toolName: String,
        toolCallContext: ToolCallContext,
        hookContext: HookContext
    ): String? {
        if (toolApprovalPolicy == null) return null
        if (!toolApprovalPolicy.requiresApproval(toolName, toolCallContext.toolParams)) return null

        val approvalStore = pendingApprovalStore
            ?: return "Tool call blocked: Approval store unavailable for required tool '$toolName'"
                .also { logger.error { it } }

        logger.info { "Tool '$toolName' requires human approval, suspending execution..." }
        return requestAndProcessApproval(approvalStore, toolName, toolCallContext, hookContext)
    }

    /** 승인을 요청하고 응답을 처리합니다. 승인 시 null, 거부/오류 시 메시지 반환. */
    private suspend fun requestAndProcessApproval(
        approvalStore: PendingApprovalStore,
        toolName: String,
        toolCallContext: ToolCallContext,
        hookContext: HookContext
    ): String? {
        val hitlStartNanos = System.nanoTime()
        return try {
            val response = approvalStore.requestApproval(
                runId = hookContext.runId,
                userId = hookContext.userId,
                toolName = toolName,
                arguments = toolCallContext.toolParams
            )
            recordApprovalMetadata(hookContext, toolCallContext, toolName, hitlStartNanos, response)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Approval check failed for tool '$toolName': ${e.message ?: "unknown error"}" }
            "Tool call blocked: Approval check failed for tool '$toolName'"
        }
    }

    /** 승인 응답 결과를 HookContext 메타데이터에 기록하고, 거부 시 메시지를 반환합니다. */
    private fun recordApprovalMetadata(
        hookContext: HookContext,
        toolCallContext: ToolCallContext,
        toolName: String,
        hitlStartNanos: Long,
        response: com.arc.reactor.approval.ToolApprovalResponse
    ): String? {
        val keySuffix = hitlMetadataSuffix(toolCallContext)
        val hitlWaitMs = (System.nanoTime() - hitlStartNanos) / 1_000_000
        hookContext.metadata["hitlWaitMs_$keySuffix"] = hitlWaitMs
        hookContext.metadata["hitlApproved_$keySuffix"] = response.approved
        if (response.approved) {
            logger.info { "Tool '$toolName' approved by human (waited ${hitlWaitMs}ms)" }
            return null
        }
        val reason = response.reason ?: "Rejected by human"
        logger.info { "Tool '$toolName' rejected by human: $reason (waited ${hitlWaitMs}ms)" }
        hookContext.metadata["hitlRejectionReason_$keySuffix"] = reason
        return "Tool call rejected by human: $reason"
    }

    /** 차단된 Tool 호출 결과를 AfterToolCallHook으로 알리고 메트릭에 기록합니다. */
    private suspend fun publishBlockedToolCallResult(
        context: ToolCallContext,
        toolName: String,
        message: String
    ) {
        hookExecutor?.executeAfterToolCall(
            context = context,
            result = ToolCallResult(
                success = false, output = message, errorMessage = message, durationMs = 0
            )
        )
        agentMetrics.recordToolCall(toolName, 0, false)
    }

    private fun hitlMetadataSuffix(context: ToolCallContext): String {
        return "${context.toolName}_${context.callIndex}"
    }

    // ──────────────────────────────────────────────
    // Tool 어댑터 호출
    // ──────────────────────────────────────────────

    /**
     * Tool 어댑터를 호출합니다. 캐시 확인 -> 실행 -> 출력 길이 제한을 적용합니다.
     *
     * @see invokeToolAdapterRaw 실제 Tool 호출 수행
     */
    private suspend fun invokeToolAdapter(
        toolName: String,
        toolInput: String,
        tools: List<Any>,
        springCallbacksByName: Map<String, org.springframework.ai.tool.ToolCallback>
    ): ToolInvocationOutcome {
        val cached = checkToolResultCache(toolName, toolInput)
        if (cached != null) return cached

        val raw = invokeToolAdapterRaw(toolName, toolInput, tools, springCallbacksByName)
        if (raw.success) storeToolResultCache(toolName, toolInput, raw.output)
        return truncateIfExceeded(toolName, raw)
    }

    /** 출력 길이 초과 시 잘라냅니다. */
    private fun truncateIfExceeded(toolName: String, outcome: ToolInvocationOutcome): ToolInvocationOutcome {
        if (maxToolOutputLength <= 0 || outcome.output.length <= maxToolOutputLength) return outcome
        logger.warn { "Tool '$toolName' output truncated: ${outcome.output.length} -> $maxToolOutputLength chars" }
        return outcome.copy(
            output = outcome.output.take(maxToolOutputLength) +
                "\n[TRUNCATED: output exceeded $maxToolOutputLength characters]"
        )
    }

    /**
     * 도구 출력의 토큰 수를 추정하고, 컨텍스트 윈도우의 30%를 초과하면 경고합니다.
     *
     * 추정된 토큰 수는 hookContext 메타데이터에 저장되어 응답 메타데이터에서 확인할 수 있습니다.
     * @return 추정된 토큰 수. TokenEstimator가 없으면 null.
     */
    private fun estimateAndWarnToolOutputTokens(
        toolName: String,
        output: String,
        hookContext: HookContext
    ): Int? {
        val estimator = tokenEstimator ?: return null
        val estimatedTokens = estimator.estimate(output)
        val threshold = (maxContextWindowTokens * TOOL_OUTPUT_TOKEN_WARNING_RATIO).toInt()
        val percentage = calculateToolOutputPercentage(estimatedTokens)
        if (estimatedTokens > threshold) {
            logger.warn {
                "도구 출력 토큰 경고: tool=$toolName, " +
                    "estimatedTokens=$estimatedTokens, " +
                    "threshold=$threshold " +
                    "(${TOOL_OUTPUT_TOKEN_WARNING_RATIO_PERCENT}% of $maxContextWindowTokens), " +
                    "usage=${percentage}%"
            }
        }
        hookContext.metadata["toolOutputTokenEstimate_$toolName"] = estimatedTokens
        return estimatedTokens
    }

    /** 추정 토큰 수가 컨텍스트 윈도우에서 차지하는 비율(%)을 계산합니다. */
    private fun calculateToolOutputPercentage(estimatedTokens: Int): Int {
        if (maxContextWindowTokens <= 0) return 0
        return estimatedTokens * 100 / maxContextWindowTokens
    }

    /**
     * Tool 어댑터를 실제로 호출합니다.
     *
     * 우선순위: ArcToolCallbackAdapter -> Spring AI ToolCallback -> 미발견 에러
     * 각 호출은 개별 타임아웃이 적용되며, CancellationException은 반드시 재throw합니다.
     */
    private suspend fun invokeToolAdapterRaw(
        toolName: String,
        toolInput: String,
        tools: List<Any>,
        springCallbacksByName: Map<String, org.springframework.ai.tool.ToolCallback>
    ): ToolInvocationOutcome {
        findToolAdapter(toolName, tools)?.let { adapter ->
            return invokeArcAdapter(toolName, toolInput, adapter)
        }
        springCallbacksByName[toolName]?.let { callback ->
            return invokeSpringCallback(toolName, toolInput, callback)
        }
        logger.warn { "Tool '$toolName' not found (possibly hallucinated by LLM)" }
        return ToolInvocationOutcome(output = toolNotFoundMessage(toolName), success = false, trackAsUsed = false)
    }

    /** ArcToolCallbackAdapter를 타임아웃 내에서 호출합니다. */
    private suspend fun invokeArcAdapter(
        toolName: String,
        toolInput: String,
        adapter: ArcToolCallbackAdapter
    ): ToolInvocationOutcome {
        return try {
            val timeoutMs = adapter.arcCallback.timeoutMs ?: toolCallTimeoutMs
            val output = withTimeout(timeoutMs) { adapter.call(toolInput) }
            val success = !output.startsWith(ReActLoopUtils.TOOL_ERROR_PREFIX)
            ToolInvocationOutcome(output = output, success = success, trackAsUsed = true)
        } catch (e: TimeoutCancellationException) {
            val timeoutMs = adapter.arcCallback.timeoutMs ?: toolCallTimeoutMs
            logger.error { "Tool $toolName timed out after ${timeoutMs}ms" }
            timeoutErrorOutcome(toolName, timeoutMs)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Tool $toolName execution failed" }
            executionErrorOutcome(e)
        }
    }

    /** Spring AI ToolCallback을 IO 디스패처에서 타임아웃 내에 호출합니다. */
    private suspend fun invokeSpringCallback(
        toolName: String,
        toolInput: String,
        callback: org.springframework.ai.tool.ToolCallback
    ): ToolInvocationOutcome {
        return try {
            val output = withTimeout(toolCallTimeoutMs) {
                runInterruptible(Dispatchers.IO) { callback.call(toolInput) }
            }
            ToolInvocationOutcome(
                output = normalizeSpringToolOutput(output), success = true, trackAsUsed = true
            )
        } catch (e: TimeoutCancellationException) {
            logger.error { "Tool $toolName timed out after ${toolCallTimeoutMs}ms" }
            timeoutErrorOutcome(toolName, toolCallTimeoutMs)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Tool $toolName execution failed" }
            executionErrorOutcome(e)
        }
    }

    private fun toolNotAllowedMessage(toolName: String): String =
        "Error: Tool '$toolName' is not allowed for this request"

    private fun toolNotFoundMessage(toolName: String): String =
        "Error: Tool '$toolName' not found"

    /** 타임아웃 에러 결과를 생성합니다. */
    private fun timeoutErrorOutcome(toolName: String, timeoutMs: Long): ToolInvocationOutcome {
        return ToolInvocationOutcome(
            output = "Error: Tool '$toolName' timed out after ${timeoutMs}ms",
            success = false, trackAsUsed = true
        )
    }

    /** 실행 에러 결과를 생성합니다. */
    private fun executionErrorOutcome(e: Exception): ToolInvocationOutcome {
        return ToolInvocationOutcome(
            output = "Error: ${e.message ?: "Unknown error"}",
            success = false, trackAsUsed = true
        )
    }

    // ──────────────────────────────────────────────
    // Spring AI ToolCallback 해석
    // ──────────────────────────────────────────────

    /** 등록된 Tool 목록에서 이름으로 ArcToolCallbackAdapter를 찾습니다. */
    private fun findToolAdapter(toolName: String, tools: List<Any>): ArcToolCallbackAdapter? {
        return tools.firstNotNullOfOrNull {
            (it as? ArcToolCallbackAdapter)?.takeIf { a -> a.arcCallback.name == toolName }
        }
    }

    /** LocalTool과 명시적 콜백에서 Spring AI ToolCallback 맵을 해석합니다 (캐시 적용). */
    private fun resolveSpringToolCallbacksByName(
        tools: List<Any>
    ): Map<String, org.springframework.ai.tool.ToolCallback> {
        val localTools = tools.filterIsInstance<LocalTool>()
            .map { unwrapAopProxy(it) }
            .distinctBy { System.identityHashCode(it) }
        val explicitCallbacks = tools
            .filterIsInstance<org.springframework.ai.tool.ToolCallback>()
            .filterNot { it is ArcToolCallbackAdapter }
        val cacheKey = localTools.sumOf { System.identityHashCode(it) } * 31 +
            explicitCallbacks.sumOf { System.identityHashCode(it) }
        return springToolCallbackCache.get(cacheKey) {
            buildSpringToolCallbacksByName(localTools, explicitCallbacks)
        }
    }

    internal fun springToolCallbackCacheEntryCount(): Int {
        springToolCallbackCache.cleanUp()
        return springToolCallbackCache.estimatedSize().toInt()
    }

    private fun buildSpringToolCallbacksByName(
        localTools: List<Any>,
        explicitCallbacks: List<org.springframework.ai.tool.ToolCallback>
    ): Map<String, org.springframework.ai.tool.ToolCallback> {
        val reflectedCallbacks = resolveReflectedCallbacks(localTools)
        val byName = LinkedHashMap<String, org.springframework.ai.tool.ToolCallback>()
        for (callback in explicitCallbacks + reflectedCallbacks) {
            val name = callback.toolDefinition.name()
            if (name.isNotBlank()) byName.putIfAbsent(name, callback)
        }
        return byName
    }

    /** LocalTool에서 @Tool 어노테이션 기반 콜백을 리플렉션으로 해석합니다. */
    private fun resolveReflectedCallbacks(
        localTools: List<Any>
    ): List<org.springframework.ai.tool.ToolCallback> {
        if (localTools.isEmpty()) return emptyList()
        return runCatching {
            MethodToolCallbackProvider.builder()
                .toolObjects(*localTools.toTypedArray())
                .build()
                .toolCallbacks
                .toList()
        }.getOrElse { ex ->
            logger.warn(ex) {
                "Failed to resolve @Tool callbacks from LocalTool beans; skipping local tool callback map."
            }
            emptyList()
        }
    }

    /** Spring AOP 프록시를 언래핑하여 원본 객체를 반환합니다. */
    private fun unwrapAopProxy(bean: Any): Any {
        if (bean !is Advised) return bean
        return runCatching { bean.targetSource.target }.getOrNull() ?: bean
    }

    // ──────────────────────────────────────────────
    // 요청자 인식 Tool 파라미터 보강
    // ──────────────────────────────────────────────

    /**
     * 요청자 인식 Tool에 assigneeAccountId 또는 requesterEmail을 자동 주입합니다.
     *
     * Tool 파라미터에 이미 해당 값이 있으면 건너뛰고,
     * 없으면 메타데이터에서 추출하여 추가합니다.
     */
    private fun enrichToolParamsForRequesterAwareTools(
        toolName: String,
        toolParams: Map<String, Any?>,
        metadata: Map<String, Any>
    ): Map<String, Any?> {
        if (toolName !in requesterAwareToolNames) return toolParams
        if (hasExistingRequesterParam(toolParams)) return toolParams
        val identifier = findOrExtractRequesterIdentifier(metadata) ?: return toolParams
        return toolParams + identifier
    }

    /** 이미 assigneeAccountId 또는 requesterEmail 파라미터가 있는지 확인합니다. */
    private fun hasExistingRequesterParam(toolParams: Map<String, Any?>): Boolean {
        val hasAssignee = toolParams["assigneeAccountId"]?.toString()?.isNotBlank() == true
        val hasEmail = toolParams["requesterEmail"]?.toString()?.isNotBlank() == true
        return hasAssignee || hasEmail
    }

    /**
     * 메타데이터에서 요청자 식별자(accountId 또는 email)를 통합 검색합니다.
     *
     * accountId 계열 키를 우선 탐색하고, 없으면 email 계열 키를 탐색합니다.
     * @return 파라미터 이름과 값의 쌍. 식별자를 찾지 못하면 null.
     */
    private fun findOrExtractRequesterIdentifier(metadata: Map<String, Any>): Pair<String, String>? {
        findMetadataValue(metadata, requesterAccountIdMetadataKeys)?.let {
            return "assigneeAccountId" to it
        }
        findMetadataValue(metadata, requesterEmailMetadataKeys)?.let {
            return "requesterEmail" to it
        }
        return null
    }

    /** 지정된 키 목록에서 첫 번째 유효한 메타데이터 값을 찾습니다. */
    private fun findMetadataValue(metadata: Map<String, Any>, keys: List<String>): String? {
        return keys.asSequence()
            .mapNotNull { key -> metadata[key]?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
    }

    // ──────────────────────────────────────────────
    // 유틸리티: 직렬화 / 응답 빌더 / 캐시
    // ──────────────────────────────────────────────

    private fun serializeToolInput(toolParams: Map<String, Any?>, rawInput: String?): String {
        if (toolParams.isEmpty()) return rawInput.orEmpty().ifBlank { "{}" }
        return runCatching { objectMapper.writeValueAsString(toolParams) }
            .getOrElse { rawInput.orEmpty().ifBlank { "{}" } }
    }

    /** Tool 실행 결과를 ToolResponseMessage.ToolResponse로 변환합니다. */
    private fun buildToolResponse(
        toolCall: AssistantMessage.ToolCall,
        toolName: String,
        output: String,
        normalizeToolResponseToJson: Boolean
    ): ToolResponseMessage.ToolResponse {
        val responseData = if (normalizeToolResponseToJson) {
            ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider(output)
        } else {
            output
        }
        return ToolResponseMessage.ToolResponse(toolCall.id(), toolName, responseData)
    }

    private fun normalizeSpringToolOutput(output: String): String {
        return runCatching { objectMapper.readValue(output, String::class.java) }
            .getOrElse { output }
    }

    /** 병렬 실행 결과를 수집하여 toolsUsed 누적과 ToolCapture 병합을 수행합니다. */
    private fun collectParallelResults(
        executions: List<ParallelToolExecution>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>
    ): List<ToolResponseMessage.ToolResponse> {
        for (execution in executions) {
            execution.usedToolName?.let(toolsUsed::add)
            mergeToolCapture(hookContext, execution.capture)
        }
        return executions.map(ParallelToolExecution::response)
    }

    // ──────────────────────────────────────────────
    // Tool 결과 캐시
    // ──────────────────────────────────────────────

    private fun buildToolResultCacheIfEnabled(): Cache<String, String>? {
        if (!toolResultCacheProperties.enabled) return null
        return Caffeine.newBuilder()
            .maximumSize(toolResultCacheProperties.maxSize)
            .expireAfterWrite(java.time.Duration.ofSeconds(toolResultCacheProperties.ttlSeconds))
            .build()
    }

    /** SHA-256 기반 캐시 키 — 32비트 hashCode() 충돌 방지. 코루틴 안전을 위해 호출마다 새 인스턴스 생성. */
    private fun buildToolResultCacheKey(toolName: String, toolInput: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest("$toolName:$toolInput".toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun checkToolResultCache(toolName: String, toolInput: String): ToolInvocationOutcome? {
        val cache = toolResultCache ?: return null
        val cacheKey = buildToolResultCacheKey(toolName, toolInput)
        val cachedOutput = cache.getIfPresent(cacheKey) ?: run {
            agentMetrics.recordToolResultCacheMiss(toolName, cacheKey)
            return null
        }
        logger.debug { "Tool result cache hit: tool=$toolName key=$cacheKey" }
        agentMetrics.recordToolResultCacheHit(toolName, cacheKey)
        return ToolInvocationOutcome(output = cachedOutput, success = true, trackAsUsed = true)
    }

    private fun storeToolResultCache(toolName: String, toolInput: String, output: String) {
        val cache = toolResultCache ?: return
        cache.put(buildToolResultCacheKey(toolName, toolInput), output)
    }

    // ──────────────────────────────────────────────
    // 상수 / 내부 데이터 클래스
    // ──────────────────────────────────────────────

    companion object {
        const val TOOL_SIGNALS_METADATA_KEY = "toolSignals"
        const val DEFAULT_MAX_TOOL_OUTPUT_LENGTH = 50_000
        const val DEFAULT_MAX_CONTEXT_WINDOW_TOKENS = 128_000
        const val TOOL_OUTPUT_TOKEN_WARNING_RATIO = 0.3
        private const val TOOL_OUTPUT_TOKEN_WARNING_RATIO_PERCENT =
            (TOOL_OUTPUT_TOKEN_WARNING_RATIO * 100).toInt()
        private val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        private val requesterAccountIdMetadataKeys = listOf("requesterAccountId", "accountId")
        private val requesterEmailMetadataKeys = listOf("requesterEmail", "userEmail", "slackUserEmail")
    }

    private data class ToolCapture(
        val verifiedSources: List<VerifiedSource> = emptyList(),
        val signal: ToolResponseSignal? = null
    )

    private data class ParallelToolExecution(
        val response: ToolResponseMessage.ToolResponse,
        val usedToolName: String? = null,
        val capture: ToolCapture = ToolCapture()
    )

    private data class ToolInvocationOutcome(
        val output: String,
        val success: Boolean,
        val trackAsUsed: Boolean
    )

}
