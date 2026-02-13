package com.arc.reactor.guard.output.impl

import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Configurable output guard stage that checks LLM responses against regex patterns.
 *
 * Each pattern has an action:
 * - **MASK**: Replace matched text with `[REDACTED]`
 * - **REJECT**: Block the response entirely
 *
 * ## Configuration Example
 * ```yaml
 * arc:
 *   reactor:
 *     output-guard:
 *       enabled: true
 *       custom-patterns:
 *         - pattern: "(?i)internal\\s+use\\s+only"
 *           action: REJECT
 *           name: "Internal Document Leak"
 *         - pattern: "(?i)password\\s*[:=]\\s*\\S+"
 *           action: MASK
 *           name: "Password Leak"
 * ```
 */
class RegexPatternOutputGuard(
    private val patterns: List<OutputBlockPattern>
) : OutputGuardStage {

    override val stageName = "RegexPattern"
    override val order = 20

    private val compiledPatterns: List<CompiledPattern> = patterns.map {
        CompiledPattern(
            name = it.name,
            regex = Regex(it.pattern),
            action = it.action
        )
    }

    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
        var masked = content
        val maskedNames = mutableListOf<String>()

        for (pattern in compiledPatterns) {
            if (!pattern.regex.containsMatchIn(masked)) continue

            when (pattern.action) {
                PatternAction.REJECT -> {
                    logger.warn { "RegexPattern '${pattern.name}' matched, rejecting response" }
                    return OutputGuardResult.Rejected(
                        reason = "Response blocked: ${pattern.name}",
                        category = OutputRejectionCategory.POLICY_VIOLATION
                    )
                }
                PatternAction.MASK -> {
                    maskedNames.add(pattern.name)
                    masked = pattern.regex.replace(masked, "[REDACTED]")
                }
            }
        }

        if (maskedNames.isEmpty()) {
            return OutputGuardResult.Allowed.DEFAULT
        }

        logger.info { "RegexPattern masked: ${maskedNames.joinToString(", ")}" }
        return OutputGuardResult.Modified(
            content = masked,
            reason = "Pattern masked: ${maskedNames.joinToString(", ")}"
        )
    }

    private data class CompiledPattern(val name: String, val regex: Regex, val action: PatternAction)
}

/**
 * Configuration for a single blocking/masking pattern.
 */
data class OutputBlockPattern(
    /** Human-readable name for logging and metrics */
    val name: String = "",
    /** Regex pattern string */
    val pattern: String = "",
    /** Action to take when pattern matches */
    val action: PatternAction = PatternAction.MASK
)

/**
 * Action to take when a pattern matches.
 */
enum class PatternAction {
    /** Replace matched text with [REDACTED] */
    MASK,
    /** Block the entire response */
    REJECT
}
