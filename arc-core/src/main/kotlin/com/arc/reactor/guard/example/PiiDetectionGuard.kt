package com.arc.reactor.guard.example

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.PiiPatterns
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory

/**
 * PII Detection Guard (example) — Regex-based GuardStage
 *
 * Rejects requests if the user input contains PII (email, phone number,
 * resident registration number, etc.) to prevent personal information
 * from being sent to the LLM.
 *
 * ## Notes
 * This example is based on simple regex patterns. For production, consider:
 * - More sophisticated pattern matching (e.g., integration with libraries like Presidio)
 * - Masking PII instead of blocking the request entirely
 * - Adding country-specific PII patterns
 *
 * ## How to activate
 * Adding @Component will auto-register this guard.
 */
// @Component  ← Uncomment to auto-register
class PiiDetectionGuard : GuardStage {

    override val stageName = "PiiDetection"

    // After InputValidation(2), before InjectionDetection(3)
    override val order = 25

    override suspend fun check(command: GuardCommand): GuardResult {
        for (pattern in PiiPatterns.ALL) {
            if (pattern.regex.containsMatchIn(command.text)) {
                return GuardResult.Rejected(
                    reason = "개인정보(${pattern.name})가 포함된 요청은 처리할 수 없습니다. " +
                        "개인정보를 제거한 후 다시 시도해주세요.",
                    category = RejectionCategory.INVALID_INPUT,
                    stage = stageName
                )
            }
        }
        return GuardResult.Allowed.DEFAULT
    }
}
