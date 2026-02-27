package com.arc.reactor.guard.impl

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.audit.GuardAuditPublisher
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 5-Stage Guardrail Pipeline
 *
 * Executes registered GuardStages in order.
 * Stops immediately if any stage returns Rejected.
 *
 * ## Error Handling Policy: Fail-Close
 * If any guard stage throws an exception, the pipeline **rejects** the request
 * with a system error. This fail-close behavior ensures security is never
 * bypassed by a malfunctioning stage.
 *
 * This contrasts with the [com.arc.reactor.hook.HookExecutor], which defaults to
 * fail-open (configurable via [com.arc.reactor.hook.AgentHook.failOnError]).
 *
 * ```
 * [UnicodeNormalization] → [RateLimit] → [InputValidation] → [InjectionDetection]
 *            → [Classification] → [Permission] → Allowed
 * ```
 */
class GuardPipeline(
    stages: List<GuardStage> = emptyList(),
    private val auditPublisher: GuardAuditPublisher? = null
) : RequestGuard {

    private val sortedStages: List<GuardStage> = stages
        .filter { it.enabled }
        .sortedBy { it.order }

    override suspend fun guard(command: GuardCommand): GuardResult {
        if (sortedStages.isEmpty()) {
            logger.debug { "No guard stages enabled, allowing request" }
            return GuardResult.Allowed.DEFAULT
        }

        val pipelineStartNanos = System.nanoTime()
        var currentCommand = command

        for (stage in sortedStages) {
            val stageStartNanos = System.nanoTime()
            try {
                logger.debug { "Executing guard stage: ${stage.stageName}" }
                val result = stage.check(currentCommand)

                when (result) {
                    is GuardResult.Allowed -> {
                        val stageLatencyMs = (System.nanoTime() - stageStartNanos) / 1_000_000
                        logger.debug { "Stage ${stage.stageName} passed" }

                        // Apply normalized text from hints (e.g., UnicodeNormalization)
                        val normalizedText = result.hints.firstOrNull {
                            it.startsWith("normalized:")
                        }?.removePrefix("normalized:")
                        if (normalizedText != null) {
                            currentCommand = currentCommand.copy(text = normalizedText)
                        }

                        auditPublisher?.publish(
                            command = currentCommand,
                            stage = stage.stageName,
                            result = "allowed",
                            reason = null,
                            stageLatencyMs = stageLatencyMs,
                            pipelineLatencyMs = (System.nanoTime() - pipelineStartNanos) / 1_000_000
                        )
                        continue
                    }
                    is GuardResult.Rejected -> {
                        val stageLatencyMs = (System.nanoTime() - stageStartNanos) / 1_000_000
                        logger.warn { "Stage ${stage.stageName} rejected: ${result.reason}" }
                        auditPublisher?.publish(
                            command = currentCommand,
                            stage = stage.stageName,
                            result = "rejected",
                            reason = result.reason,
                            category = result.category.name,
                            stageLatencyMs = stageLatencyMs,
                            pipelineLatencyMs = (System.nanoTime() - pipelineStartNanos) / 1_000_000
                        )
                        return result.copy(stage = stage.stageName)
                    }
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                val stageLatencyMs = (System.nanoTime() - stageStartNanos) / 1_000_000
                logger.error(e) { "Guard stage ${stage.stageName} failed" }
                auditPublisher?.publish(
                    command = currentCommand,
                    stage = stage.stageName,
                    result = "error",
                    reason = e.message,
                    stageLatencyMs = stageLatencyMs,
                    pipelineLatencyMs = (System.nanoTime() - pipelineStartNanos) / 1_000_000
                )
                // fail-close: reject on error
                return GuardResult.Rejected(
                    reason = "Security check failed",
                    category = RejectionCategory.SYSTEM_ERROR,
                    stage = stage.stageName
                )
            }
        }

        val pipelineLatencyMs = (System.nanoTime() - pipelineStartNanos) / 1_000_000
        auditPublisher?.publish(
            command = currentCommand,
            stage = "pipeline",
            result = "allowed",
            reason = null,
            stageLatencyMs = 0,
            pipelineLatencyMs = pipelineLatencyMs
        )
        return GuardResult.Allowed.DEFAULT
    }
}
