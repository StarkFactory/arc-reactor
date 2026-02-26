package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.response.ResponseFilterChain
import com.arc.reactor.response.ResponseFilterContext
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

internal class ExecutionResultFinalizer(
    private val outputGuardPipeline: OutputGuardPipeline?,
    private val responseFilterChain: ResponseFilterChain?,
    private val boundaries: BoundaryProperties,
    private val conversationManager: ConversationManager,
    private val hookExecutor: HookExecutor?,
    private val errorMessageResolver: ErrorMessageResolver,
    private val agentMetrics: AgentMetrics,
    private val outputBoundaryEnforcer: OutputBoundaryEnforcer =
        OutputBoundaryEnforcer(boundaries = boundaries, agentMetrics = agentMetrics),
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    suspend fun finalize(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long,
        attemptLongerResponse: suspend (String, Int, AgentCommand) -> String?
    ): AgentResult {
        val guarded = applyOutputGuardPipeline(result, command, toolsUsed, startTime)
        if (!guarded.success && guarded.errorCode == AgentErrorCode.OUTPUT_GUARD_REJECTED) {
            runAfterCompletionHook(hookContext, guarded, toolsUsed, startTime)
            return guarded
        }

        val bounded = applyOutputBoundaryRule(guarded, command, startTime, attemptLongerResponse)
        if (!bounded.success && bounded.errorCode == AgentErrorCode.OUTPUT_TOO_SHORT) {
            runAfterCompletionHook(hookContext, bounded, toolsUsed, startTime)
            return bounded
        }

        val filtered = applyResponseFilters(bounded, command, toolsUsed, startTime)
        conversationManager.saveHistory(command, filtered)
        runAfterCompletionHook(hookContext, filtered, toolsUsed, startTime)
        return recordFinalExecution(filtered, startTime)
    }

    private suspend fun applyOutputGuardPipeline(
        result: AgentResult,
        command: AgentCommand,
        toolsUsed: List<String>,
        startTime: Long
    ): AgentResult {
        if (!result.success || result.content == null || outputGuardPipeline == null) return result

        return try {
            val guardContext = OutputGuardContext(
                command = command,
                toolsUsed = toolsUsed,
                durationMs = nowMs() - startTime
            )
            when (val guardResult = outputGuardPipeline.check(result.content, guardContext)) {
                is OutputGuardResult.Allowed -> {
                    agentMetrics.recordOutputGuardAction("pipeline", "allowed", "", command.metadata)
                    result
                }

                is OutputGuardResult.Modified -> {
                    agentMetrics.recordOutputGuardAction(
                        guardResult.stage ?: "unknown",
                        "modified",
                        guardResult.reason,
                        command.metadata
                    )
                    result.copy(content = guardResult.content)
                }

                is OutputGuardResult.Rejected -> {
                    agentMetrics.recordOutputGuardAction(
                        guardResult.stage ?: "unknown",
                        "rejected",
                        guardResult.reason,
                        command.metadata
                    )
                    outputGuardFailure(reason = guardResult.reason, startTime = startTime)
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Output guard pipeline failed, rejecting (fail-close)" }
            outputGuardFailure(reason = "Output guard check failed", startTime = startTime)
        }
    }

    private suspend fun applyOutputBoundaryRule(
        result: AgentResult,
        command: AgentCommand,
        startTime: Long,
        attemptLongerResponse: suspend (String, Int, AgentCommand) -> String?
    ): AgentResult {
        if (!result.success || result.content == null) return result

        return outputBoundaryEnforcer.apply(result, command, attemptLongerResponse)
            ?: outputTooShortFailure(startTime)
    }

    private suspend fun applyResponseFilters(
        result: AgentResult,
        command: AgentCommand,
        toolsUsed: List<String>,
        startTime: Long
    ): AgentResult {
        if (!result.success || result.content == null || responseFilterChain == null) return result

        return try {
            val context = ResponseFilterContext(
                command = command,
                toolsUsed = toolsUsed,
                durationMs = nowMs() - startTime
            )
            val filteredContent = responseFilterChain.apply(result.content, context)
            result.copy(content = filteredContent)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Response filter chain failed, using original content" }
            result
        }
    }

    private suspend fun runAfterCompletionHook(
        hookContext: HookContext,
        result: AgentResult,
        toolsUsed: List<String>,
        startTime: Long
    ) {
        try {
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(
                    success = result.success,
                    response = result.content,
                    errorMessage = result.errorMessage,
                    toolsUsed = toolsUsed,
                    totalDurationMs = nowMs() - startTime,
                    errorCode = result.errorCode?.name
                )
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "AfterAgentComplete hook failed" }
        }
    }

    private fun recordFinalExecution(result: AgentResult, startTime: Long): AgentResult {
        val finalResult = result.copy(durationMs = nowMs() - startTime)
        agentMetrics.recordExecution(finalResult)
        return finalResult
    }

    private fun outputGuardFailure(reason: String, startTime: Long): AgentResult {
        return AgentResult.failure(
            errorMessage = reason,
            errorCode = AgentErrorCode.OUTPUT_GUARD_REJECTED,
            durationMs = nowMs() - startTime
        ).also { agentMetrics.recordExecution(it) }
    }

    private fun outputTooShortFailure(startTime: Long): AgentResult {
        return AgentResult.failure(
            errorMessage = errorMessageResolver.resolve(AgentErrorCode.OUTPUT_TOO_SHORT, null),
            errorCode = AgentErrorCode.OUTPUT_TOO_SHORT,
            durationMs = nowMs() - startTime
        ).also { agentMetrics.recordExecution(it) }
    }
}
