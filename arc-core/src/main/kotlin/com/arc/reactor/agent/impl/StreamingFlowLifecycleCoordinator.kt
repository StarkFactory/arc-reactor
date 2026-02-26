package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.hook.model.HookContext

internal class StreamingFlowLifecycleCoordinator(
    private val streamingCompletionFinalizer: StreamingCompletionFinalizer,
    private val agentMetrics: AgentMetrics,
    private val closeRunContext: () -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    suspend fun finalize(
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        state: StreamingExecutionState,
        startTime: Long,
        emit: suspend (String) -> Unit
    ) {
        try {
            streamingCompletionFinalizer.finalize(
                command = command,
                hookContext = hookContext,
                streamStarted = state.streamStarted,
                streamSuccess = state.streamSuccess,
                collectedContent = state.collectedContent.toString(),
                lastIterationContent = state.lastIterationContent.toString(),
                streamErrorMessage = state.streamErrorMessage,
                streamErrorCode = state.streamErrorCode?.name,
                toolsUsed = toolsUsed,
                startTime = startTime,
                emit = emit
            )
            recordStreamingMetrics(state, toolsUsed, startTime)
        } finally {
            closeRunContext()
        }
    }

    private fun recordStreamingMetrics(
        state: StreamingExecutionState,
        toolsUsed: List<String>,
        startTime: Long
    ) {
        val durationMs = nowMs() - startTime
        val result = if (state.streamSuccess) {
            AgentResult.success(
                content = state.collectedContent.toString(),
                toolsUsed = toolsUsed,
                durationMs = durationMs
            )
        } else {
            AgentResult.failure(
                errorMessage = state.streamErrorMessage ?: "Streaming failed",
                errorCode = state.streamErrorCode ?: AgentErrorCode.UNKNOWN,
                durationMs = durationMs
            )
        }
        agentMetrics.recordStreamingExecution(result)
    }
}
