package com.arc.reactor.guard.impl

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 5단계 가드레일 파이프라인
 *
 * 등록된 GuardStage들을 순서대로 실행.
 * 각 단계에서 Rejected 반환 시 즉시 중단.
 *
 * ```
 * [RateLimit] → [InputValidation] → [InjectionDetection]
 *            → [Classification] → [Permission] → Allowed
 * ```
 */
class GuardPipeline(
    private val stages: List<GuardStage> = emptyList()
) : RequestGuard {

    override suspend fun guard(command: GuardCommand): GuardResult {
        val sortedStages = stages
            .filter { it.enabled }
            .sortedBy { it.order }

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
                logger.error(e) { "Guard stage ${stage.stageName} failed" }
                // fail-close: 에러 시 거부
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
