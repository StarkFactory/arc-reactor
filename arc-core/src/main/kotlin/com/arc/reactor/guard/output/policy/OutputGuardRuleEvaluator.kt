package com.arc.reactor.guard.output.policy

/**
 * Shared evaluator for regex-based output guard policies.
 */
class OutputGuardRuleEvaluator {

    fun evaluate(content: String, rules: List<OutputGuardRule>): OutputGuardEvaluation {
        if (rules.isEmpty()) return OutputGuardEvaluation.allowed(content)

        var maskedContent = content
        val matched = mutableListOf<OutputGuardRuleMatch>()
        val invalid = mutableListOf<InvalidOutputGuardRule>()

        for (rule in rules) {
            val regex = runCatching { Regex(rule.pattern) }.getOrElse {
                invalid.add(
                    InvalidOutputGuardRule(
                        ruleId = rule.id,
                        ruleName = rule.name,
                        reason = it.message ?: "invalid regex"
                    )
                )
                continue
            }

            if (!regex.containsMatchIn(maskedContent)) continue
            val ruleMatch = OutputGuardRuleMatch(
                ruleId = rule.id,
                ruleName = rule.name,
                action = rule.action,
                priority = rule.priority
            )
            matched.add(ruleMatch)

            when (rule.action) {
                OutputGuardRuleAction.REJECT -> {
                    return OutputGuardEvaluation(
                        blocked = true,
                        content = maskedContent,
                        matchedRules = matched.toList(),
                        blockedBy = ruleMatch,
                        invalidRules = invalid.toList()
                    )
                }

                OutputGuardRuleAction.MASK -> {
                    maskedContent = regex.replace(maskedContent, "[REDACTED]")
                }
            }
        }

        return OutputGuardEvaluation(
            blocked = false,
            content = maskedContent,
            matchedRules = matched.toList(),
            blockedBy = null,
            invalidRules = invalid.toList()
        )
    }
}

data class OutputGuardRuleMatch(
    val ruleId: String,
    val ruleName: String,
    val action: OutputGuardRuleAction,
    val priority: Int
)

data class InvalidOutputGuardRule(
    val ruleId: String,
    val ruleName: String,
    val reason: String
)

data class OutputGuardEvaluation(
    val blocked: Boolean,
    val content: String,
    val matchedRules: List<OutputGuardRuleMatch>,
    val blockedBy: OutputGuardRuleMatch?,
    val invalidRules: List<InvalidOutputGuardRule>
) {
    val modified: Boolean get() = !blocked && matchedRules.any { it.action == OutputGuardRuleAction.MASK }

    companion object {
        fun allowed(content: String): OutputGuardEvaluation = OutputGuardEvaluation(
            blocked = false,
            content = content,
            matchedRules = emptyList(),
            blockedBy = null,
            invalidRules = emptyList()
        )
    }
}
