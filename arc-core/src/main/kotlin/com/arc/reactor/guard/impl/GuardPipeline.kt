package com.arc.reactor.guard.impl

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.RequestGuard
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
 * [RateLimit] → [InputValidation] → [InjectionDetection]
 *            → [Classification] → [Permission] → Allowed
 * ```
 */
class GuardPipeline(
    stages: List<GuardStage> = emptyList()
) : RequestGuard {

    private val sortedStages: List<GuardStage> = stages
        .filter { it.enabled }
        .sortedBy { it.order }

    override suspend fun guard(command: GuardCommand): GuardResult {
        if (sortedStages.isEmpty()) {
            logger.debug { "No guard stages enabled, allowing request" }
            return GuardResult.Allowed.DEFAULT
        }

        for (stage in sortedStages) {
            try {
                logger.debug { "Executing guard stage: ${stage.stageName}" }
                val result = stage.check(command)

                when (result) {
                    is GuardResult.Allowed -> {
                        logger.debug { "Stage ${stage.stageName} passed" }
                        continue
                    }
                    is GuardResult.Rejected -> {
                        logger.warn { "Stage ${stage.stageName} rejected: ${result.reason}" }
                        return result.copy(stage = stage.stageName)
                    }
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Guard stage ${stage.stageName} failed" }
                // fail-close: reject on error
                return GuardResult.Rejected(
                    reason = "Security check failed",
                    category = RejectionCategory.SYSTEM_ERROR,
                    stage = stage.stageName
                )
            }
        }

        return GuardResult.Allowed.DEFAULT
    }
}
