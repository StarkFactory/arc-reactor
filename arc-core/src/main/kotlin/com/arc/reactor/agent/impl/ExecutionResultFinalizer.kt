package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
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
            applyOutputBoundary(guarded, command, attemptLongerResponse)
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
            logger.error(e) { "AfterAgentComplete hook failed" }
        }

        val finalResult = filtered.copy(durationMs = nowMs() - startTime)
        agentMetrics.recordExecution(finalResult)
        return finalResult
    }

    private suspend fun applyOutputBoundary(
        result: AgentResult,
        command: AgentCommand,
        attemptLongerResponse: suspend (String, Int, AgentCommand) -> String?
    ): AgentResult? {
        val content = result.content ?: return result
        val len = content.length

        val afterMax = if (boundaries.outputMaxChars > 0 && len > boundaries.outputMaxChars) {
            agentMetrics.recordBoundaryViolation(
                "output_too_long", "truncate", boundaries.outputMaxChars, len
            )
            logger.info { "Output truncated: $len chars exceeds max ${boundaries.outputMaxChars}" }
            result.copy(content = content.take(boundaries.outputMaxChars) + "\n\n[Response truncated]")
        } else {
            result
        }

        val effectiveContent = afterMax.content ?: return afterMax
        if (boundaries.outputMinChars <= 0 || effectiveContent.length >= boundaries.outputMinChars) {
            return afterMax
        }

        return when (boundaries.outputMinViolationMode) {
            OutputMinViolationMode.WARN -> {
                agentMetrics.recordBoundaryViolation(
                    "output_too_short", "warn", boundaries.outputMinChars, effectiveContent.length
                )
                logger.warn {
                    "Output too short: ${effectiveContent.length} chars " +
                        "(min: ${boundaries.outputMinChars}), passing through (WARN)"
                }
                afterMax
            }

            OutputMinViolationMode.RETRY_ONCE -> {
                agentMetrics.recordBoundaryViolation(
                    "output_too_short", "retry", boundaries.outputMinChars, effectiveContent.length
                )
                logger.info {
                    "Output too short: ${effectiveContent.length} chars " +
                        "(min: ${boundaries.outputMinChars}), retrying once"
                }
                val retried = attemptLongerResponse(effectiveContent, boundaries.outputMinChars, command)
                if (retried != null && retried.length >= boundaries.outputMinChars) {
                    afterMax.copy(content = retried)
                } else {
                    logger.warn { "Retry still too short, falling back to WARN" }
                    afterMax
                }
            }

            OutputMinViolationMode.FAIL -> {
                agentMetrics.recordBoundaryViolation(
                    "output_too_short", "fail", boundaries.outputMinChars, effectiveContent.length
                )
                logger.warn {
                    "Output too short: ${effectiveContent.length} chars " +
                        "(min: ${boundaries.outputMinChars}), failing (FAIL mode)"
                }
                null
            }
        }
    }
}
