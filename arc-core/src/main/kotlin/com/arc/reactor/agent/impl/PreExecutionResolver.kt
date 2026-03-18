package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 에이전트 실행 전(pre-execution) 사전 검증을 수행하는 resolver.
 *
 * ReAct 루프 진입 전에 다음 단계를 순서대로 실행한다:
 * 1. **Guard 검사**: 요청이 허용되는지 확인 (rate limit, 콘텐츠 필터 등)
 * 2. **BeforeStart 훅**: 사전 훅에서 요청을 차단할 수 있음
 * 3. **인텐트 해석**: 사용자 의도를 분류하고 프로필을 적용
 *
 * Guard는 fail-close(거부 = 차단), Hook은 fail-open이 기본이지만
 * HookResult.Reject 반환 시 차단된다.
 *
 * @see SpringAiAgentExecutor ReAct 루프 진입 전 이 resolver를 호출
 * @see RequestGuard 요청 가드 체인 (GuardStage 파이프라인)
 * @see HookExecutor BeforeStart 훅 실행기
 * @see IntentResolver 인텐트 분류 및 프로필 적용
 * @see StageTimingSupport 각 단계의 소요 시간 기록
 */
internal class PreExecutionResolver(
    private val guard: RequestGuard?,
    private val hookExecutor: HookExecutor?,
    private val intentResolver: IntentResolver?,
    private val blockedIntents: Set<String>,
    private val agentMetrics: AgentMetrics,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val tracer: ArcReactorTracer = NoOpArcReactorTracer()
) {

    /**
     * Guard 검사를 실행한다. 거부 시 [GuardResult.Rejected]를 반환하고, 허용 시 null을 반환한다.
     *
     * @param command 에이전트 명령 (userId가 null이면 "anonymous"로 대체)
     * @return 거부 결과 또는 null(허용)
     */
    suspend fun checkGuard(command: AgentCommand): GuardResult.Rejected? {
        if (guard == null) return null
        val userId = command.userId ?: "anonymous"
        val result = guard.guard(
            GuardCommand(
                userId = userId,
                text = command.userPrompt,
                channel = command.metadata["channel"]?.toString(),
                systemPrompt = command.systemPrompt,
                metadata = command.metadata
            )
        )
        return result as? GuardResult.Rejected
    }

    /**
     * BeforeAgentStart 훅을 실행한다. 훅이 차단하면 [HookResult.Reject]를 반환하고, 계속 진행 시 null을 반환한다.
     *
     * @param hookContext 훅 컨텍스트
     * @return 차단 결과 또는 null(계속)
     */
    suspend fun checkBeforeHooks(hookContext: HookContext): HookResult.Reject? {
        if (hookExecutor == null) return null
        return hookExecutor.executeBeforeAgentStart(hookContext) as? HookResult.Reject
    }

    /**
     * Guard 검사와 BeforeStart 훅을 통합 실행한다.
     *
     * 거부 시 실패 [AgentResult]를 반환하고, 모두 통과하면 null을 반환한다.
     * 각 단계의 소요 시간을 메트릭과 hookContext에 기록한다.
     *
     * @param command 에이전트 명령
     * @param hookContext 훅 컨텍스트 (타이밍 기록용)
     * @param startTime 전체 실행 시작 시각
     * @return 거부 시 실패 결과, 통과 시 null
     */
    suspend fun checkGuardAndHooks(
        command: AgentCommand,
        hookContext: HookContext,
        startTime: Long
    ): AgentResult? {
        val guardSpan = tracer.startSpan("arc.agent.guard")
        val guardStartTime = nowMs()
        try {
            // ── 단계 1: Guard 검사 ──
            checkGuard(command)?.let { rejection ->
                val guardDurationMs = nowMs() - guardStartTime
                hookContext.metadata[HookMetadataKeys.GUARD_DURATION_MS] = guardDurationMs
                recordStageTiming(hookContext, "guard", guardDurationMs)
                agentMetrics.recordStageLatency("guard", guardDurationMs, command.metadata)
                guardSpan.setAttribute("guard.result", "rejected")
                agentMetrics.recordGuardRejection(
                    stage = rejection.stage ?: "unknown",
                    reason = rejection.reason,
                    metadata = command.metadata
                )
                val errorCode = when (rejection.category) {
                    RejectionCategory.RATE_LIMITED -> AgentErrorCode.RATE_LIMITED
                    else -> AgentErrorCode.GUARD_REJECTED
                }
                return AgentResult.failure(
                    errorMessage = rejection.reason,
                    errorCode = errorCode,
                    durationMs = nowMs() - startTime
                ).also { agentMetrics.recordExecution(it) }
            }
            val guardDurationMs = nowMs() - guardStartTime
            hookContext.metadata[HookMetadataKeys.GUARD_DURATION_MS] = guardDurationMs
            recordStageTiming(hookContext, "guard", guardDurationMs)
            agentMetrics.recordStageLatency("guard", guardDurationMs, command.metadata)
            guardSpan.setAttribute("guard.result", "passed")

            // ── 단계 2: BeforeStart 훅 검사 ──
            val beforeHooksStartTime = nowMs()
            checkBeforeHooks(hookContext)?.let { rejection ->
                val beforeHooksDurationMs = nowMs() - beforeHooksStartTime
                recordStageTiming(hookContext, "before_hooks", beforeHooksDurationMs)
                agentMetrics.recordStageLatency("before_hooks", beforeHooksDurationMs, command.metadata)
                return AgentResult.failure(
                    errorMessage = rejection.reason,
                    errorCode = AgentErrorCode.HOOK_REJECTED,
                    durationMs = nowMs() - startTime
                ).also { agentMetrics.recordExecution(it) }
            }
            val beforeHooksDurationMs = nowMs() - beforeHooksStartTime
            recordStageTiming(hookContext, "before_hooks", beforeHooksDurationMs)
            agentMetrics.recordStageLatency("before_hooks", beforeHooksDurationMs, command.metadata)
        } finally {
            guardSpan.close()
        }
        return null
    }

    /**
     * 인텐트를 분류하고 해당 프로필을 명령에 적용한다.
     *
     * Fail-safe: 차단된 인텐트([BlockedIntentException])를 제외한 모든 에러에서는 원본 명령을 그대로 반환한다.
     * 이미 인텐트 해석이 완료된 명령(metadata에 표시)은 재해석하지 않고 기존 결과를 사용한다.
     *
     * @param command 에이전트 명령
     * @param hookContext 훅 컨텍스트 (타이밍 기록용)
     * @return 인텐트 프로필이 적용된 명령 (또는 원본 명령)
     * @throws BlockedIntentException 차단 대상 인텐트로 분류된 경우
     */
    suspend fun resolveIntent(command: AgentCommand, hookContext: HookContext): AgentCommand {
        if (intentResolver == null) return command
        if (intentResolutionAlreadyAttempted(command)) {
            val durationMs = resolvePriorIntentResolutionDuration(command)
            recordResolvedIntentDuration(command, hookContext, durationMs)
            val resolvedIntentName = command.metadata[IntentResolver.METADATA_INTENT_NAME]?.toString()
            if (resolvedIntentName != null && blockedIntents.contains(resolvedIntentName)) {
                throw BlockedIntentException(resolvedIntentName)
            }
            return command
        }
        val intentStartTime = nowMs()
        try {
            val context = ClassificationContext(
                userId = command.userId,
                channel = command.metadata["channel"]?.toString()
            )
            val resolved = intentResolver.resolve(command.userPrompt, context)
                ?: return command.also {
                    recordMeasuredIntentResolutionDuration(command, hookContext, intentStartTime)
                }
            if (blockedIntents.contains(resolved.intentName)) {
                recordMeasuredIntentResolutionDuration(command, hookContext, intentStartTime)
                throw BlockedIntentException(resolved.intentName)
            }
            return intentResolver.applyProfile(command, resolved).also {
                recordMeasuredIntentResolutionDuration(command, hookContext, intentStartTime)
            }
        } catch (e: BlockedIntentException) {
            throw e
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Intent resolution failed, using original command" }
            return command.also {
                recordMeasuredIntentResolutionDuration(command, hookContext, intentStartTime)
            }
        }
    }

    /** 현재 시간 기준으로 인텐트 해석 소요 시간을 계산하여 기록한다. */
    private fun recordMeasuredIntentResolutionDuration(
        command: AgentCommand,
        hookContext: HookContext,
        intentStartTime: Long
    ) {
        val durationMs = nowMs() - intentStartTime
        recordResolvedIntentDuration(command, hookContext, durationMs)
    }

    /** 인텐트 해석 소요 시간을 단계 타이밍과 메트릭에 기록한다. */
    private fun recordResolvedIntentDuration(command: AgentCommand, hookContext: HookContext, durationMs: Long) {
        recordStageTiming(hookContext, "intent_resolution", durationMs)
        agentMetrics.recordStageLatency("intent_resolution", durationMs, command.metadata)
    }

    /** 이 명령에 대해 이미 인텐트 해석이 시도되었는지 metadata에서 확인한다. */
    private fun intentResolutionAlreadyAttempted(command: AgentCommand): Boolean {
        val raw = command.metadata[IntentResolver.METADATA_INTENT_RESOLUTION_ATTEMPTED] ?: return false
        return when (raw) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> false
        }
    }

    /** 이전에 수행된 인텐트 해석의 소요 시간을 metadata에서 추출한다. */
    private fun resolvePriorIntentResolutionDuration(command: AgentCommand): Long {
        val raw = command.metadata[IntentResolver.METADATA_INTENT_RESOLUTION_DURATION_MS] ?: return 0L
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}
