package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
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
     * Run guard check. Returns rejection result if rejected, null if allowed.
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
     * Run before-agent-start hooks. Returns rejection if blocked, null if continue.
     */
    suspend fun checkBeforeHooks(hookContext: HookContext): HookResult.Reject? {
        if (hookExecutor == null) return null
        return hookExecutor.executeBeforeAgentStart(hookContext) as? HookResult.Reject
    }

    suspend fun checkGuardAndHooks(
        command: AgentCommand,
        hookContext: HookContext,
        startTime: Long
    ): AgentResult? {
        val guardSpan = tracer.startSpan("arc.agent.guard")
        val guardStartTime = nowMs()
        try {
            checkGuard(command)?.let { rejection ->
                val guardDurationMs = nowMs() - guardStartTime
                hookContext.metadata["guardDurationMs"] = guardDurationMs
                recordStageTiming(hookContext, "guard", guardDurationMs)
                agentMetrics.recordStageLatency("guard", guardDurationMs, command.metadata)
                guardSpan.setAttribute("guard.result", "rejected")
                agentMetrics.recordGuardRejection(
                    stage = rejection.stage ?: "unknown",
                    reason = rejection.reason,
                    metadata = command.metadata
                )
                return AgentResult.failure(
                    errorMessage = rejection.reason,
                    errorCode = AgentErrorCode.GUARD_REJECTED,
                    durationMs = nowMs() - startTime
                ).also { agentMetrics.recordExecution(it) }
            }
            val guardDurationMs = nowMs() - guardStartTime
            hookContext.metadata["guardDurationMs"] = guardDurationMs
            recordStageTiming(hookContext, "guard", guardDurationMs)
            agentMetrics.recordStageLatency("guard", guardDurationMs, command.metadata)
            guardSpan.setAttribute("guard.result", "passed")
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
     * Resolve intent and apply profile to the command.
     *
     * Fail-safe: on any error (except blocked intents), returns the original command.
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

    private fun recordMeasuredIntentResolutionDuration(
        command: AgentCommand,
        hookContext: HookContext,
        intentStartTime: Long
    ) {
        val durationMs = nowMs() - intentStartTime
        recordResolvedIntentDuration(command, hookContext, durationMs)
    }

    private fun recordResolvedIntentDuration(command: AgentCommand, hookContext: HookContext, durationMs: Long) {
        recordStageTiming(hookContext, "intent_resolution", durationMs)
        agentMetrics.recordStageLatency("intent_resolution", durationMs, command.metadata)
    }

    private fun intentResolutionAlreadyAttempted(command: AgentCommand): Boolean {
        val raw = command.metadata[IntentResolver.METADATA_INTENT_RESOLUTION_ATTEMPTED] ?: return false
        return when (raw) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun resolvePriorIntentResolutionDuration(command: AgentCommand): Long {
        val raw = command.metadata[IntentResolver.METADATA_INTENT_RESOLUTION_DURATION_MS] ?: return 0L
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}
