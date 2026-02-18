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
        // Step 1: Apply output guard pipeline (fail-close, before response filter)
        val guarded = if (result.success && result.content != null && outputGuardPipeline != null) {
            try {
                val guardContext = OutputGuardContext(
                    command = command,
                    toolsUsed = toolsUsed,
                    durationMs = nowMs() - startTime
                )
                when (val guardResult = outputGuardPipeline.check(result.content, guardContext)) {
                    is OutputGuardResult.Allowed -> {
                        agentMetrics.recordOutputGuardAction("pipeline", "allowed", "")
                        result
                    }

                    is OutputGuardResult.Modified -> {
                        agentMetrics.recordOutputGuardAction(
                            guardResult.stage ?: "unknown",
                            "modified",
                            guardResult.reason
                        )
                        result.copy(content = guardResult.content)
                    }

                    is OutputGuardResult.Rejected -> {
                        agentMetrics.recordOutputGuardAction(
                            guardResult.stage ?: "unknown",
                            "rejected",
                            guardResult.reason
                        )
                        return AgentResult.failure(
                            errorMessage = guardResult.reason,
                            errorCode = AgentErrorCode.OUTPUT_GUARD_REJECTED,
                            durationMs = nowMs() - startTime
                        ).also { agentMetrics.recordExecution(it) }
                    }
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Output guard pipeline failed, rejecting (fail-close)" }
                return AgentResult.failure(
                    errorMessage = "Output guard check failed",
                    errorCode = AgentErrorCode.OUTPUT_GUARD_REJECTED,
                    durationMs = nowMs() - startTime
                ).also { agentMetrics.recordExecution(it) }
            }
        } else {
            result
        }

        // Step 1.5: Apply output boundary check (between output guard and response filter)
        val bounded = if (guarded.success && guarded.content != null) {
            outputBoundaryEnforcer.apply(guarded, command, attemptLongerResponse)
                ?: return AgentResult.failure(
                    errorMessage = errorMessageResolver.resolve(AgentErrorCode.OUTPUT_TOO_SHORT, null),
                    errorCode = AgentErrorCode.OUTPUT_TOO_SHORT,
                    durationMs = nowMs() - startTime
                ).also { agentMetrics.recordExecution(it) }
        } else {
            guarded
        }

        // Step 2: Apply response filter chain (fail-open, after output guard)
        val filtered = if (bounded.success && bounded.content != null && responseFilterChain != null) {
            try {
                val context = ResponseFilterContext(
                    command = command,
                    toolsUsed = toolsUsed,
                    durationMs = nowMs() - startTime
                )
                val filteredContent = responseFilterChain.apply(bounded.content, context)
                bounded.copy(content = filteredContent)
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "Response filter chain failed, using original content" }
                bounded
            }
        } else {
            bounded
        }

        conversationManager.saveHistory(command, filtered)
        try {
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(
                    success = filtered.success,
                    response = filtered.content,
                    errorMessage = filtered.errorMessage,
                    toolsUsed = toolsUsed
                )
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "AfterAgentComplete hook failed" }
        }

        val finalResult = filtered.copy(durationMs = nowMs() - startTime)
        agentMetrics.recordExecution(finalResult)
        return finalResult
    }
}
