package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.support.formatBoundaryViolation
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

internal class StreamingCompletionFinalizer(
    private val boundaries: BoundaryProperties,
    private val conversationManager: ConversationManager,
    private val hookExecutor: HookExecutor?,
    private val agentMetrics: AgentMetrics,
    private val outputGuardPipeline: OutputGuardPipeline? = null,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    suspend fun finalize(
        command: AgentCommand,
        hookContext: HookContext,
        streamStarted: Boolean,
        streamSuccess: Boolean,
        collectedContent: String,
        lastIterationContent: String,
        streamErrorMessage: String?,
        streamErrorCode: String?,
        toolsUsed: List<String>,
        startTime: Long,
        emit: suspend (String) -> Unit
    ) {
        if (streamSuccess) {
            applyStreamingOutputGuard(command, collectedContent, toolsUsed, startTime, emit)
            conversationManager.saveStreamingHistory(command, lastIterationContent)
            emitBoundaryMarkers(collectedContent, emit)
        }

        try {
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(
                    success = streamSuccess,
                    response = if (streamSuccess) collectedContent else null,
                    errorMessage = if (!streamSuccess) (streamErrorMessage ?: "Streaming failed") else null,
                    toolsUsed = toolsUsed,
                    totalDurationMs = nowMs() - startTime,
                    errorCode = if (!streamSuccess) streamErrorCode else null
                )
            )
        } catch (hookEx: Exception) {
            hookEx.throwIfCancellation()
            logger.error(hookEx) { "AfterAgentComplete hook failed in streaming finally" }
        }
    }

    private suspend fun applyStreamingOutputGuard(
        command: AgentCommand,
        collectedContent: String,
        toolsUsed: List<String>,
        startTime: Long,
        emit: suspend (String) -> Unit
    ) {
        if (outputGuardPipeline == null || collectedContent.isEmpty()) return

        try {
            val guardContext = OutputGuardContext(
                command = command,
                toolsUsed = toolsUsed,
                durationMs = nowMs() - startTime
            )
            when (val result = outputGuardPipeline.check(collectedContent, guardContext)) {
                is OutputGuardResult.Allowed -> {
                    agentMetrics.recordOutputGuardAction("pipeline", "allowed", "", command.metadata)
                }
                is OutputGuardResult.Modified -> {
                    agentMetrics.recordOutputGuardAction(
                        result.stage ?: "unknown", "modified", result.reason, command.metadata
                    )
                    logger.warn { "Streaming output guard modified content: ${result.reason}" }
                    emit(StreamEventMarker.error(
                        "Output guard modified response: ${result.reason}"
                    ))
                }
                is OutputGuardResult.Rejected -> {
                    agentMetrics.recordOutputGuardAction(
                        result.stage ?: "unknown", "rejected", result.reason, command.metadata
                    )
                    logger.warn { "Streaming output guard rejected: ${result.reason}" }
                    emit(StreamEventMarker.error(
                        "Output guard rejected response: ${result.reason}"
                    ))
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Streaming output guard failed" }
            emit(StreamEventMarker.error("Output guard check failed"))
        }
    }

    private suspend fun emitBoundaryMarkers(
        collectedContent: String,
        emit: suspend (String) -> Unit
    ) {
        val contentLength = collectedContent.length

        if (boundaries.outputMaxChars > 0 && contentLength > boundaries.outputMaxChars) {
            val policy = "warn"
            agentMetrics.recordBoundaryViolation(
                "output_too_long", policy, boundaries.outputMaxChars, contentLength
            )
            logger.warn { formatBoundaryViolation("output_too_long", policy, boundaries.outputMaxChars, contentLength) }
            try {
                emit(StreamEventMarker.error(
                    formatBoundaryViolation("output_too_long", policy, boundaries.outputMaxChars, contentLength)
                ))
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.debug { "Could not emit boundary error (collector cancelled)" }
            }
        }

        if (boundaries.outputMinChars > 0 && contentLength < boundaries.outputMinChars) {
            val policy = when (boundaries.outputMinViolationMode) {
                OutputMinViolationMode.RETRY_ONCE -> "warn" // falls back to warn in streaming
                else -> boundaries.outputMinViolationMode.name.lowercase()
            }
            agentMetrics.recordBoundaryViolation(
                "output_too_short", policy, boundaries.outputMinChars, contentLength
            )
            logger.warn {
                formatBoundaryViolation(
                    "output_too_short",
                    policy,
                    boundaries.outputMinChars,
                    contentLength
                )
            }
            try {
                emit(StreamEventMarker.error(
                    formatBoundaryViolation("output_too_short", policy, boundaries.outputMinChars, contentLength)
                ))
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.debug { "Could not emit boundary error (collector cancelled)" }
            }
        }
    }
}
