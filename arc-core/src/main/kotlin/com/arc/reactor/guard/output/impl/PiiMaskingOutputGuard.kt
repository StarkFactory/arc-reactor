package com.arc.reactor.guard.output.impl

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

        for (pattern in PII_PATTERNS) {
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

    private data class PiiPattern(val name: String, val regex: Regex, val mask: String)

    companion object {
        private val PII_PATTERNS = listOf(
            // Korean resident registration number (000000-0000000)
            PiiPattern(
                name = "주민등록번호",
                regex = Regex("""\d{6}\s?-\s?[1-4]\d{6}"""),
                mask = "******-*******"
            ),
            // Credit card number (0000-0000-0000-0000 or 0000000000000000)
            PiiPattern(
                name = "신용카드번호",
                regex = Regex("""\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}"""),
                mask = "****-****-****-****"
            ),
            // Korean phone number (010-0000-0000)
            PiiPattern(
                name = "전화번호",
                regex = Regex("""01[016789]-?\d{3,4}-?\d{4}"""),
                mask = "***-****-****"
            ),
            // Email address
            PiiPattern(
                name = "이메일",
                regex = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""),
                mask = "***@***.***"
            )
        )
    }
}
