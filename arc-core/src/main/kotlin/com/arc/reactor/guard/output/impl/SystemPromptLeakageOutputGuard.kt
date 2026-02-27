package com.arc.reactor.guard.output.impl

import com.arc.reactor.guard.canary.CanaryTokenProvider
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * System Prompt Leakage Output Guard (order=5)
 *
 * Detects system prompt leakage in LLM output via:
 * 1. Canary token presence check
 * 2. Common leakage pattern matching
 */
class SystemPromptLeakageOutputGuard(
    private val canaryTokenProvider: CanaryTokenProvider? = null
) : OutputGuardStage {

    override val stageName = "SystemPromptLeakage"
    override val order = 5

    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
        // Check 1: Canary token leak
        if (canaryTokenProvider != null && canaryTokenProvider.containsToken(content)) {
            logger.warn { "Canary token detected in output — system prompt leakage" }
            return OutputGuardResult.Rejected(
                reason = "System prompt leakage detected (canary token found)",
                category = OutputRejectionCategory.POLICY_VIOLATION,
                stage = stageName
            )
        }

        // Check 2: Leakage pattern detection
        for (pattern in LEAKAGE_PATTERNS) {
            if (pattern.containsMatchIn(content)) {
                logger.warn { "System prompt leakage pattern detected: ${pattern.pattern}" }
                return OutputGuardResult.Rejected(
                    reason = "Potential system prompt leakage detected",
                    category = OutputRejectionCategory.POLICY_VIOLATION,
                    stage = stageName
                )
            }
        }

        return OutputGuardResult.Allowed.DEFAULT
    }

    companion object {
        private val LEAKAGE_PATTERNS = listOf(
            Regex("(?i)my (full |complete |actual |real )?system prompt (is|says|reads|contains|was)"),
            Regex("(?i)here (is|are) my (full |complete |original |initial )?(system )?(prompt|instructions)"),
            Regex("(?i)my (original|initial) (system )?(prompt|instructions) (are|say|tell|read)"),
            Regex("(?i)I('m| am) (not )?supposed to (reveal|share|show|tell|disclose).*(prompt|instructions)"),
            Regex("(?i)the (original |initial |full |complete )?system prompt (says|reads|contains|is|was)")
        )
    }
}
