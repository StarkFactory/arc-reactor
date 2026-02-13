package com.arc.reactor.guard.output.impl

import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import com.arc.reactor.guard.output.policy.OutputGuardRule
import com.arc.reactor.guard.output.policy.OutputGuardRuleAction
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Dynamic regex-based output guard backed by [OutputGuardRuleStore].
 *
 * Rules can be changed at runtime via admin API without application restart.
 */
class DynamicRuleOutputGuard(
    private val store: OutputGuardRuleStore,
    private val refreshIntervalMs: Long = 3000
) : OutputGuardStage {

    override val stageName: String = "DynamicRule"
    override val order: Int = 15

    @Volatile
    private var cachedAtMs: Long = 0

    @Volatile
    private var cachedRules: List<CompiledRule> = emptyList()

    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
        val rules = getCompiledRules()
        if (rules.isEmpty()) return OutputGuardResult.Allowed.DEFAULT

        var masked = content
        val maskedNames = mutableListOf<String>()

        for (rule in rules) {
            if (!rule.regex.containsMatchIn(masked)) continue

            when (rule.action) {
                OutputGuardRuleAction.REJECT -> {
                    logger.warn { "Dynamic output rule '${rule.name}' matched, rejecting response" }
                    return OutputGuardResult.Rejected(
                        reason = "Response blocked: ${rule.name}",
                        category = OutputRejectionCategory.POLICY_VIOLATION,
                        stage = stageName
                    )
                }

                OutputGuardRuleAction.MASK -> {
                    maskedNames.add(rule.name)
                    masked = rule.regex.replace(masked, "[REDACTED]")
                }
            }
        }

        if (maskedNames.isEmpty()) return OutputGuardResult.Allowed.DEFAULT

        return OutputGuardResult.Modified(
            content = masked,
            reason = "Dynamic rule masked: ${maskedNames.joinToString(", ")}",
            stage = stageName
        )
    }

    private fun getCompiledRules(nowMs: Long = System.currentTimeMillis()): List<CompiledRule> {
        val interval = refreshIntervalMs.coerceAtLeast(200)
        if (nowMs - cachedAtMs <= interval) return cachedRules

        synchronized(this) {
            if (nowMs - cachedAtMs <= interval) return cachedRules

            cachedRules = store.list()
                .asSequence()
                .filter { it.enabled }
                .mapNotNull(::compile)
                .toList()
            cachedAtMs = nowMs
            return cachedRules
        }
    }

    private fun compile(rule: OutputGuardRule): CompiledRule? {
        return try {
            CompiledRule(
                name = rule.name,
                action = rule.action,
                regex = Regex(rule.pattern)
            )
        } catch (e: Exception) {
            logger.warn(e) { "Skipping invalid dynamic output rule id=${rule.id}, name=${rule.name}" }
            null
        }
    }

    private data class CompiledRule(
        val name: String,
        val action: OutputGuardRuleAction,
        val regex: Regex
    )
}
