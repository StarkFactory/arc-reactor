package com.arc.reactor.guard.output

import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Executes a list of [OutputGuardStage]s in order on LLM response content.
 *
 * Stages are sorted by [OutputGuardStage.order] (ascending).
 * This pipeline is **fail-close**: if a stage throws an exception,
 * the response is rejected with [OutputRejectionCategory.SYSTEM_ERROR].
 *
 * ## Execution Flow
 * For each stage:
 * - [OutputGuardResult.Allowed] → continue to next stage
 * - [OutputGuardResult.Modified] → update content, continue with modified content
 * - [OutputGuardResult.Rejected] → stop immediately and return rejection
 * - Exception → reject with SYSTEM_ERROR (fail-close)
 *
 * If all stages pass, returns the final [OutputGuardResult] (Allowed or last Modified).
 */
class OutputGuardPipeline(
    stages: List<OutputGuardStage>,
    private val onStageComplete: ((stage: String, action: String, reason: String) -> Unit)? = null
) {

    private val sorted: List<OutputGuardStage> = stages
        .filter { it.enabled }
        .sortedBy { it.order }

    /**
     * Run all output guard stages on the given content.
     *
     * @param content LLM response content
     * @param context Request and execution metadata
     * @return Final guard result after all stages
     */
    suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
        if (sorted.isEmpty()) return OutputGuardResult.Allowed.DEFAULT

        var currentContent = content
        var lastModified: OutputGuardResult.Modified? = null

        for (stage in sorted) {
            val result = try {
                stage.check(currentContent, context)
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "OutputGuardStage '${stage.stageName}' failed, rejecting (fail-close)" }
                return OutputGuardResult.Rejected(
                    reason = "Output guard check failed: ${stage.stageName}",
                    category = OutputRejectionCategory.SYSTEM_ERROR,
                    stage = stage.stageName
                )
            }

            when (result) {
                is OutputGuardResult.Allowed -> {
                    onStageComplete?.invoke(stage.stageName, "allowed", "")
                    continue
                }
                is OutputGuardResult.Modified -> {
                    logger.info { "OutputGuardStage '${stage.stageName}' modified content: ${result.reason}" }
                    onStageComplete?.invoke(stage.stageName, "modified", result.reason)
                    currentContent = result.content
                    lastModified = result.copy(stage = stage.stageName)
                }
                is OutputGuardResult.Rejected -> {
                    logger.warn { "OutputGuardStage '${stage.stageName}' rejected: ${result.reason}" }
                    onStageComplete?.invoke(stage.stageName, "rejected", result.reason)
                    return result.copy(stage = stage.stageName)
                }
            }
        }

        return lastModified ?: OutputGuardResult.Allowed.DEFAULT
    }

    /** Number of active stages in the pipeline. */
    val size: Int get() = sorted.size
}
