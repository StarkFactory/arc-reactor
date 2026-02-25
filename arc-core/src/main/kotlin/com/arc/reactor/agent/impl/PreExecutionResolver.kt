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
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

internal class PreExecutionResolver(
    private val guard: RequestGuard?,
    private val hookExecutor: HookExecutor?,
    private val intentResolver: IntentResolver?,
    private val blockedIntents: Set<String>,
    private val agentMetrics: AgentMetrics,
    private val nowMs: () -> Long = System::currentTimeMillis
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
                systemPrompt = command.systemPrompt
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
        checkGuard(command)?.let { rejection ->
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
        checkBeforeHooks(hookContext)?.let { rejection ->
            return AgentResult.failure(
                errorMessage = rejection.reason,
                errorCode = AgentErrorCode.HOOK_REJECTED,
                durationMs = nowMs() - startTime
            ).also { agentMetrics.recordExecution(it) }
        }
        return null
    }

    /**
     * Resolve intent and apply profile to the command.
     *
     * Fail-safe: on any error (except blocked intents), returns the original command.
     */
    suspend fun resolveIntent(command: AgentCommand): AgentCommand {
        if (intentResolver == null) return command
        try {
            val context = ClassificationContext(
                userId = command.userId,
                channel = command.metadata["channel"]?.toString()
            )
            val resolved = intentResolver.resolve(command.userPrompt, context)
                ?: return command
            if (blockedIntents.contains(resolved.intentName)) {
                throw BlockedIntentException(resolved.intentName)
            }
            return intentResolver.applyProfile(command, resolved)
        } catch (e: BlockedIntentException) {
            throw e
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Intent resolution failed, using original command" }
            return command
        }
    }
}
