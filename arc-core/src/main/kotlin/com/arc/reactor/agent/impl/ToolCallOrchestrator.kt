package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.ToolResultCacheProperties
import com.arc.reactor.agent.metrics.AgentMetrics
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
import java.util.concurrent.ConcurrentHashMap
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
    private val toolResultCacheProperties: ToolResultCacheProperties = ToolResultCacheProperties()
) {
    // Spring AI ToolCallback 해석 결과 캐시 — MethodToolCallbackProvider 호출 비용 절감
    private val springToolCallbackCache =
        ConcurrentHashMap<ToolCallbackCacheKey, Map<String, org.springframework.ai.tool.ToolCallback>>()

    // Tool 결과 캐시 (Caffeine) — 동일 입력에 대한 중복 Tool 호출 방지
    private val toolResultCache: Cache<String, String>? = if (toolResultCacheProperties.enabled) {
        Caffeine.newBuilder()
            .maximumSize(toolResultCacheProperties.maxSize)
            .expireAfterWrite(
                java.time.Duration.ofSeconds(toolResultCacheProperties.ttlSeconds)
            )
            .build()
    } else {
        null
    }

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
            val message = "Error: Tool '$toolName' is not allowed for this request"
            logger.info { "Direct tool call blocked by allowlist: tool=$toolName allowedTools=${allowedTools.size}" }
            agentMetrics.recordToolCall(toolName, 0, false)
            return ToolCallResult(success = false, output = message, errorMessage = message, durationMs = 0)
        }

        val effectiveToolParams = enrichToolParamsForRequesterAwareTools(
            toolName = toolName,
            toolParams = toolParams,
            metadata = hookContext.metadata
        )
        val callIndex = toolsUsed.size
        val toolCallContext = ToolCallContext(
            agentContext = hookContext,
            toolName = toolName,
            toolParams = effectiveToolParams,
            callIndex = callIndex
        )

        checkBeforeToolCallHook(toolCallContext)?.let { rejection ->
            val message = "Tool call rejected: ${rejection.reason}"
            logger.info { "Direct tool call $toolName rejected by hook: ${rejection.reason}" }
            return ToolCallResult(success = false, output = message, errorMessage = message, durationMs = 0)
        }

        checkToolApproval(toolName, toolCallContext, hookContext)?.let { rejection ->
            publishBlockedToolCallResult(toolCallContext, toolName, rejection)
            return ToolCallResult(success = false, output = rejection, errorMessage = rejection, durationMs = 0)
        }

        val toolStartTime = System.currentTimeMillis()
        val springCallbacksByName = resolveSpringToolCallbacksByName(tools)
        val toolInput = serializeToolInput(effectiveToolParams, null)
        val invocation = invokeToolAdapter(
            toolName = toolName,
            toolInput = toolInput,
            tools = tools,
            springCallbacksByName = springCallbacksByName
        )
        if (invocation.trackAsUsed) {
            toolsUsed.add(toolName)
        }
        captureToolSignals(hookContext, toolName, invocation.output, invocation.success)
        var toolOutput = invocation.output
        val toolDurationMs = System.currentTimeMillis() - toolStartTime

        if (toolOutputSanitizer != null) {
            val sanitized = toolOutputSanitizer.sanitize(toolName, toolOutput)
            toolOutput = sanitized.content
        }

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
        executions.forEach { execution ->
            execution.usedToolName?.let(toolsUsed::add)
            mergeToolCapture(hookContext, execution.capture)
        }
        executions.map(ParallelToolExecution::response)
    }

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
        if (allowedTools != null && toolName !in allowedTools) {
            val msg = "Error: Tool '$toolName' is not allowed for this request"
            logger.info { "Tool call blocked by allowlist: tool=$toolName allowedTools=${allowedTools.size}" }
            agentMetrics.recordToolCall(toolName, 0, false)
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall = toolCall,
                    toolName = toolName,
                    output = msg,
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            )
        }

        val parsedToolParams = parseToolArguments(toolCall.arguments())
        val effectiveToolParams = enrichToolParamsForRequesterAwareTools(
            toolName = toolName,
            toolParams = parsedToolParams,
            metadata = hookContext.metadata
        )
        val toolInput = serializeToolInput(effectiveToolParams, toolCall.arguments())

        val toolCallContext = ToolCallContext(
            agentContext = hookContext,
            toolName = toolName,
            toolParams = effectiveToolParams,
            callIndex = totalToolCallsCounter.get()
        )

        checkBeforeToolCallHook(toolCallContext)?.let { rejection ->
            logger.info { "Tool call $toolName rejected by hook: ${rejection.reason}" }
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall = toolCall,
                    toolName = toolName,
                    output = "Tool call rejected: ${rejection.reason}",
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            )
        }

        // Human-in-the-Loop: 승인이 필요한 Tool 호출인지 확인
        checkToolApproval(toolName, toolCallContext, hookContext)?.let { rejection ->
            publishBlockedToolCallResult(toolCallContext, toolName, rejection)
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall = toolCall,
                    toolName = toolName,
                    output = rejection,
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            )
        }

        val toolExists = findToolAdapter(toolName, tools) != null || springCallbacksByName.containsKey(toolName)
        if (!toolExists) {
            logger.warn { "Tool '$toolName' not found (possibly hallucinated by LLM)" }
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall = toolCall,
                    toolName = toolName,
                    output = "Error: Tool '$toolName' not found",
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            )
        }

        if (reserveToolExecutionSlot(totalToolCallsCounter, maxToolCalls) == null) {
            logger.warn { "maxToolCalls ($maxToolCalls) reached, stopping tool execution" }
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall = toolCall,
                    toolName = toolCall.name(),
                    output = "Error: Maximum tool call limit ($maxToolCalls) reached",
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            )
        }

        val toolStartTime = System.currentTimeMillis()
        val invocation = invokeToolAdapter(
            toolName = toolName,
            toolInput = toolInput,
            tools = tools,
            springCallbacksByName = springCallbacksByName
        )
        val capture = extractToolCapture(toolName, invocation.output, invocation.success)
        var toolOutput = invocation.output
        val toolDurationMs = System.currentTimeMillis() - toolStartTime

        // 간접 프롬프트 주입 방어를 위한 Tool 출력 새니타이징
        if (toolOutputSanitizer != null) {
            val sanitized = toolOutputSanitizer.sanitize(toolName, toolOutput)
            toolOutput = sanitized.content
        }

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
            response = buildToolResponse(
                toolCall = toolCall,
                toolName = toolName,
                output = toolOutput,
                normalizeToolResponseToJson = normalizeToolResponseToJson
            ),
            usedToolName = toolName.takeIf { invocation.trackAsUsed },
            capture = capture
        )
    }

    /**
     * CAS(Compare-And-Set) 기반 Tool 실행 슬롯 예약.
     *
     * 병렬 실행 시 maxToolCalls를 초과하지 않도록 원자적으로 카운터를 증가시킵니다.
     * @return 예약된 슬롯 인덱스, 초과 시 null
     */
    private fun reserveToolExecutionSlot(counter: AtomicInteger, maxToolCalls: Int): Int? {
        while (true) {
            val current = counter.get()
            if (current >= maxToolCalls) {
                return null
            }
            if (counter.compareAndSet(current, current + 1)) {
                return current
            }
        }
    }

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
    private fun mergeToolCapture(
        hookContext: HookContext,
        capture: ToolCapture
    ) {
        capture.verifiedSources
            .filterNot { source -> hookContext.verifiedSources.any { it.url == source.url } }
            .forEach(hookContext.verifiedSources::add)
        capture.signal?.let { signal ->
            val signals = getOrCreateToolSignals(hookContext)
            signals += signal
            signal.answerMode?.let { hookContext.metadata["answerMode"] = it }
            signal.grounded?.let { hookContext.metadata["grounded"] = it }
            signal.freshness?.let { hookContext.metadata["freshness"] = it }
            signal.retrievedAt?.let { hookContext.metadata["retrievedAt"] = it }
            signal.blockReason?.let { hookContext.metadata["blockReason"] = it }
        }
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
     * 승인/거부/타임아웃 결과를 메타데이터에 기록합니다.
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
        if (approvalStore == null) {
            val reason = "Approval store unavailable for required tool '$toolName'"
            logger.error { reason }
            return "Tool call blocked: $reason"
        }

        logger.info { "Tool '$toolName' requires human approval, suspending execution..." }
        val hitlStartNanos = System.nanoTime()

        return try {
            val response = approvalStore.requestApproval(
                runId = hookContext.runId,
                userId = hookContext.userId,
                toolName = toolName,
                arguments = toolCallContext.toolParams
            )
            val keySuffix = hitlMetadataSuffix(toolCallContext)
            val hitlWaitMs = (System.nanoTime() - hitlStartNanos) / 1_000_000
            hookContext.metadata["hitlWaitMs_$keySuffix"] = hitlWaitMs
            hookContext.metadata["hitlApproved_$keySuffix"] = response.approved
            if (response.approved) {
                logger.info { "Tool '$toolName' approved by human (waited ${hitlWaitMs}ms)" }
                null // Continue execution
            } else {
                val reason = response.reason ?: "Rejected by human"
                logger.info { "Tool '$toolName' rejected by human: $reason (waited ${hitlWaitMs}ms)" }
                hookContext.metadata["hitlRejectionReason_$keySuffix"] = reason
                "Tool call rejected by human: $reason"
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Approval check failed for tool '$toolName': ${e.message ?: "unknown error"}" }
            "Tool call blocked: Approval check failed for tool '$toolName'"
        }
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
                success = false,
                output = message,
                errorMessage = message,
                durationMs = 0
            )
        )
        agentMetrics.recordToolCall(toolName, 0, false)
    }

    private fun hitlMetadataSuffix(context: ToolCallContext): String {
        return "${context.toolName}_${context.callIndex}"
    }

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
        if (raw.success) {
            storeToolResultCache(toolName, toolInput, raw.output)
        }
        if (maxToolOutputLength > 0 && raw.output.length > maxToolOutputLength) {
            logger.warn { "Tool '$toolName' output truncated: ${raw.output.length} -> $maxToolOutputLength chars" }
            return raw.copy(
                output = raw.output.take(maxToolOutputLength) +
                    "\n[TRUNCATED: output exceeded $maxToolOutputLength characters]"
            )
        }
        return raw
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
        val adapter = findToolAdapter(toolName, tools)
        if (adapter != null) {
            return try {
                val timeoutMs = adapter.arcCallback.timeoutMs ?: toolCallTimeoutMs
                val output = withTimeout(timeoutMs) {
                    adapter.call(toolInput)
                }
                val success = !output.startsWith("Error:")
                ToolInvocationOutcome(output = output, success = success, trackAsUsed = true)
            } catch (e: TimeoutCancellationException) {
                val timeoutMs = adapter.arcCallback.timeoutMs ?: toolCallTimeoutMs
                logger.error { "Tool $toolName timed out after ${timeoutMs}ms" }
                ToolInvocationOutcome(
                    output = "Error: Tool '$toolName' timed out after ${timeoutMs}ms",
                    success = false,
                    trackAsUsed = true
                )
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Tool $toolName execution failed" }
                ToolInvocationOutcome(
                    output = "Error: ${e.message ?: "Unknown error"}",
                    success = false,
                    trackAsUsed = true
                )
            }
        }

        val springCallback = springCallbacksByName[toolName]
        if (springCallback != null) {
            return try {
                val output = withTimeout(toolCallTimeoutMs) {
                    runInterruptible(Dispatchers.IO) {
                        springCallback.call(toolInput)
                    }
                }
                ToolInvocationOutcome(
                    output = normalizeSpringToolOutput(output),
                    success = true,
                    trackAsUsed = true
                )
            } catch (e: TimeoutCancellationException) {
                logger.error { "Tool $toolName timed out after ${toolCallTimeoutMs}ms" }
                ToolInvocationOutcome(
                    output = "Error: Tool '$toolName' timed out after ${toolCallTimeoutMs}ms",
                    success = false,
                    trackAsUsed = true
                )
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Tool $toolName execution failed" }
                ToolInvocationOutcome(
                    output = "Error: ${e.message ?: "Unknown error"}",
                    success = false,
                    trackAsUsed = true
                )
            }
        }

        logger.warn { "Tool '$toolName' not found (possibly hallucinated by LLM)" }
        return ToolInvocationOutcome(
            output = "Error: Tool '$toolName' not found",
            success = false,
            trackAsUsed = false
        )
    }

    /** 등록된 Tool 목록에서 이름으로 ArcToolCallbackAdapter를 찾습니다. */
    private fun findToolAdapter(toolName: String, tools: List<Any>): ArcToolCallbackAdapter? {
        return tools.firstNotNullOfOrNull { (it as? ArcToolCallbackAdapter)?.takeIf { a -> a.arcCallback.name == toolName } }
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
        val cacheKey = ToolCallbackCacheKey(
            localToolIds = localTools.map(System::identityHashCode),
            explicitCallbackIds = explicitCallbacks.map(System::identityHashCode)
        )
        return springToolCallbackCache.computeIfAbsent(cacheKey) {
            buildSpringToolCallbacksByName(localTools, explicitCallbacks)
        }
    }

    internal fun springToolCallbackCacheEntryCount(): Int = springToolCallbackCache.size

    private fun buildSpringToolCallbacksByName(
        localTools: List<Any>,
        explicitCallbacks: List<org.springframework.ai.tool.ToolCallback>
    ): Map<String, org.springframework.ai.tool.ToolCallback> {
        val reflectedCallbacks = if (localTools.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                MethodToolCallbackProvider.builder()
                    .toolObjects(*localTools.toTypedArray())
                    .build()
                    .toolCallbacks
                    .toList()
            }.getOrElse { ex ->
                logger.warn(ex) { "Failed to resolve @Tool callbacks from LocalTool beans; skipping local tool callback map." }
                emptyList()
            }
        }

        val byName = LinkedHashMap<String, org.springframework.ai.tool.ToolCallback>()
        (explicitCallbacks + reflectedCallbacks).forEach { callback ->
            val name = callback.toolDefinition.name()
            if (name.isNotBlank()) {
                byName.putIfAbsent(name, callback)
            }
        }
        return byName
    }

    /** Spring AOP 프록시를 언래핑하여 원본 객체를 반환합니다. */
    private fun unwrapAopProxy(bean: Any): Any {
        if (bean !is Advised) return bean
        return runCatching { bean.targetSource.target }
            .getOrNull()
            ?: bean
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

        val hasAssignee = toolParams["assigneeAccountId"]?.toString()?.isNotBlank() == true
        if (hasAssignee) return toolParams

        val hasRequesterEmail = toolParams["requesterEmail"]?.toString()?.isNotBlank() == true
        if (hasRequesterEmail) return toolParams

        val assigneeAccountId = requesterAccountIdMetadataKeys.asSequence()
            .mapNotNull { key -> metadata[key]?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            .firstOrNull()

        if (!assigneeAccountId.isNullOrBlank()) {
            return toolParams + ("assigneeAccountId" to assigneeAccountId)
        }

        val requesterEmail = requesterEmailMetadataKeys.asSequence()
            .mapNotNull { key -> metadata[key]?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?: return toolParams

        return toolParams + ("requesterEmail" to requesterEmail)
    }

    private fun serializeToolInput(toolParams: Map<String, Any?>, rawInput: String?): String {
        if (toolParams.isEmpty()) {
            return rawInput.orEmpty().ifBlank { "{}" }
        }
        return runCatching {
            objectMapper.writeValueAsString(toolParams)
        }.getOrElse {
            rawInput.orEmpty().ifBlank { "{}" }
        }
    }

    private fun buildToolResultCacheKey(toolName: String, toolInput: String): String {
        return "$toolName:${toolInput.hashCode()}"
    }

    private fun checkToolResultCache(
        toolName: String,
        toolInput: String
    ): ToolInvocationOutcome? {
        val cache = toolResultCache ?: return null
        val cacheKey = buildToolResultCacheKey(toolName, toolInput)
        val cachedOutput = cache.getIfPresent(cacheKey) ?: run {
            agentMetrics.recordToolResultCacheMiss(toolName, cacheKey)
            return null
        }
        logger.debug { "Tool result cache hit: tool=$toolName key=$cacheKey" }
        agentMetrics.recordToolResultCacheHit(toolName, cacheKey)
        return ToolInvocationOutcome(
            output = cachedOutput,
            success = true,
            trackAsUsed = true
        )
    }

    private fun storeToolResultCache(
        toolName: String,
        toolInput: String,
        output: String
    ) {
        val cache = toolResultCache ?: return
        val cacheKey = buildToolResultCacheKey(toolName, toolInput)
        cache.put(cacheKey, output)
    }

    companion object {
        const val TOOL_SIGNALS_METADATA_KEY = "toolSignals"
        const val DEFAULT_MAX_TOOL_OUTPUT_LENGTH = 50_000
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

    private data class ToolCallbackCacheKey(
        val localToolIds: List<Int>,
        val explicitCallbackIds: List<Int>
    )
}
