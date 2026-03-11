package com.arc.reactor.guard.output.impl

import com.arc.reactor.guard.PiiPatterns
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Output guard stage that detects and masks PII in LLM responses.
 *
 * Supports Korean and international PII patterns:
 * - Korean resident registration number (주민등록번호)
 * - Phone numbers (Korean mobile)
 * - Credit card numbers
 * - Email addresses
 *
 * Default action is **MASK** (replace with `***`), returning [OutputGuardResult.Modified].
 *
 * ## How to activate
 * Enable via configuration:
 * ```yaml
 * arc:
 *   reactor:
 *     output-guard:
 *       enabled: true
 *       pii-masking-enabled: true
 * ```
 */
class PiiMaskingOutputGuard : OutputGuardStage {

    override val stageName = "PiiMasking"
    override val order = 10

    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
        var masked = content
        val detectedTypes = mutableListOf<String>()

        for (pattern in PiiPatterns.ALL) {
            if (pattern.regex.containsMatchIn(masked)) {
                detectedTypes.add(pattern.name)
                masked = pattern.regex.replace(masked, pattern.mask)
            }
        }

        if (detectedTypes.isEmpty()) {
            return OutputGuardResult.Allowed.DEFAULT
        }

        logger.info { "PII detected and masked: ${detectedTypes.joinToString(", ")}" }
        return OutputGuardResult.Modified(
            content = masked,
            reason = "PII masked: ${detectedTypes.joinToString(", ")}"
        )
    }
}
