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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
            val guardPassed = applyStreamingOutputGuard(command, collectedContent, toolsUsed, startTime, emit)
            if (guardPassed && lastIterationContent.isNotEmpty()) {
                conversationManager.saveStreamingHistory(command, lastIterationContent)
            }
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

        emitDoneMarker(hookContext, toolsUsed, startTime, emit)
    }

    private suspend fun emitDoneMarker(
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long,
        emit: suspend (String) -> Unit
    ) {
        try {
            val metadata = buildDoneMetadata(hookContext, toolsUsed, startTime)
            val json = if (metadata.isNotEmpty()) objectMapper.writeValueAsString(metadata) else ""
            emit(StreamEventMarker.done(json))
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.debug { "Could not emit done marker (collector cancelled)" }
        }
    }

    private fun buildDoneMetadata(
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long
    ): Map<String, Any> {
        val metadata = LinkedHashMap<String, Any>()
        val stageTimings = readStageTimings(hookContext)
        if (stageTimings.isNotEmpty()) {
            metadata["stageTimings"] = stageTimings
        }
        val ragDocCount = hookContext.metadata[RagContextRetriever.METADATA_RAG_DOCUMENT_COUNT]
        if (ragDocCount is Number && ragDocCount.toInt() > 0) {
            metadata["ragDocumentCount"] = ragDocCount.toInt()
        }
        val ragSources = hookContext.metadata[RagContextRetriever.METADATA_RAG_SOURCES]
        if (ragSources is List<*> && ragSources.isNotEmpty()) {
            metadata["ragSources"] = ragSources
        }
        if (toolsUsed.isNotEmpty()) {
            metadata["toolsUsed"] = toolsUsed
        }
        val totalDurationMs = nowMs() - startTime
        if (totalDurationMs > 0) {
            metadata["totalDurationMs"] = totalDurationMs
        }
        return metadata
    }

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }

    // Returns true if the guard passed (Allowed or Modified), false if Rejected.
    // History must only be saved when this returns true.
    private suspend fun applyStreamingOutputGuard(
        command: AgentCommand,
        collectedContent: String,
        toolsUsed: List<String>,
        startTime: Long,
        emit: suspend (String) -> Unit
    ): Boolean {
        if (outputGuardPipeline == null || collectedContent.isEmpty()) return true

        return try {
            val guardContext = OutputGuardContext(
                command = command,
                toolsUsed = toolsUsed,
                durationMs = nowMs() - startTime
            )
            when (val result = outputGuardPipeline.check(collectedContent, guardContext)) {
                is OutputGuardResult.Allowed -> {
                    agentMetrics.recordOutputGuardAction("pipeline", "allowed", "", command.metadata)
                    true
                }
                is OutputGuardResult.Modified -> {
                    agentMetrics.recordOutputGuardAction(
                        result.stage ?: "unknown", "modified", result.reason, command.metadata
                    )
                    logger.warn { "Streaming output guard modified content: ${result.reason}" }
                    emit(StreamEventMarker.error(
                        "Output guard modified response: ${result.reason}"
                    ))
                    true
                }
                is OutputGuardResult.Rejected -> {
                    agentMetrics.recordOutputGuardAction(
                        result.stage ?: "unknown", "rejected", result.reason, command.metadata
                    )
                    logger.warn { "Streaming output guard rejected: ${result.reason}" }
                    emit(StreamEventMarker.error(
                        "Output guard rejected response: ${result.reason}"
                    ))
                    false
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Streaming output guard failed, rejecting (fail-close)" }
            emit(StreamEventMarker.error("Output guard check failed"))
            false // fail-close: do not save potentially unsafe content to conversation history
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
