package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

internal class AgentExecutionFailureHandler(
    private val errorMessageResolver: ErrorMessageResolver,
    private val hookExecutor: HookExecutor?,
    private val agentMetrics: AgentMetrics,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    suspend fun handle(
        errorCode: AgentErrorCode,
        exception: Exception,
        hookContext: HookContext,
        startTime: Long
    ): AgentResult {
        val result = buildFailureResult(errorCode, exception, startTime)
        runAfterCompleteHook(result, hookContext)
        agentMetrics.recordExecution(result)
        return result
    }

    private fun buildFailureResult(
        errorCode: AgentErrorCode,
        exception: Exception,
        startTime: Long
    ): AgentResult {
        return AgentResult.failure(
            errorMessage = errorMessageResolver.resolve(errorCode, exception.message),
            errorCode = errorCode,
            durationMs = nowMs() - startTime
        )
    }

    private suspend fun runAfterCompleteHook(
        failResult: AgentResult,
        hookContext: HookContext
    ) {
        try {
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(
                    success = false,
                    errorMessage = failResult.errorMessage,
                    toolsUsed = hookContext.toolsUsed.toList(),
                    totalDurationMs = failResult.durationMs,
                    errorCode = failResult.errorCode?.name
                )
            )
        } catch (hookEx: Exception) {
            hookEx.throwIfCancellation()
            logger.error(hookEx) { "AfterAgentComplete hook failed during error handling" }
        }
    }
}
