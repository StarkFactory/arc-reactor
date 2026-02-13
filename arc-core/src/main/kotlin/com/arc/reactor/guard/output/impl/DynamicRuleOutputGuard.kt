package com.arc.reactor.guard.output.impl

import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import com.arc.reactor.guard.output.policy.OutputGuardRule
import com.arc.reactor.guard.output.policy.OutputGuardRuleAction
import com.arc.reactor.guard.output.policy.OutputGuardRuleEvaluator
import com.arc.reactor.guard.output.policy.OutputGuardRuleInvalidationBus
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
    private val refreshIntervalMs: Long = 3000,
    private val invalidationBus: OutputGuardRuleInvalidationBus = OutputGuardRuleInvalidationBus(),
    private val evaluator: OutputGuardRuleEvaluator = OutputGuardRuleEvaluator()
) : OutputGuardStage {

    override val stageName: String = "DynamicRule"
    override val order: Int = 15

    @Volatile
    private var cachedAtMs: Long = 0

    @Volatile
    private var cachedRevision: Long = -1

    @Volatile
    private var cachedRules: List<OutputGuardRule> = emptyList()

    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
        val rules = getRules()
        if (rules.isEmpty()) return OutputGuardResult.Allowed.DEFAULT

        val evaluation = evaluator.evaluate(content = content, rules = rules)
        for (invalid in evaluation.invalidRules) {
            logger.warn { "Skipping invalid dynamic output rule id=${invalid.ruleId}, name=${invalid.ruleName}" }
        }

        if (evaluation.blocked) {
            val blockedBy = evaluation.blockedBy?.ruleName ?: "unknown"
            logger.warn { "Dynamic output rule '$blockedBy' matched, rejecting response" }
            return OutputGuardResult.Rejected(
                reason = "Response blocked: $blockedBy",
                category = OutputRejectionCategory.POLICY_VIOLATION,
                stage = stageName
            )
        }

        if (!evaluation.modified) return OutputGuardResult.Allowed.DEFAULT

        return OutputGuardResult.Modified(
            content = evaluation.content,
            reason = "Dynamic rule masked: ${
                evaluation.matchedRules
                    .filter { it.action == OutputGuardRuleAction.MASK }
                    .joinToString(", ") { it.ruleName }
            }",
            stage = stageName
        )
    }

    private fun getRules(nowMs: Long = System.currentTimeMillis()): List<OutputGuardRule> {
        val interval = refreshIntervalMs.coerceAtLeast(200)
        val revision = invalidationBus.currentRevision()
        if (nowMs - cachedAtMs <= interval && revision == cachedRevision) return cachedRules

        synchronized(this) {
            val latestRevision = invalidationBus.currentRevision()
            if (nowMs - cachedAtMs <= interval && latestRevision == cachedRevision) return cachedRules

            cachedRules = store.list()
                .asSequence()
                .filter { it.enabled }
                .sortedWith(compareBy<OutputGuardRule> { it.priority }.thenBy { it.createdAt })
                .toList()
            cachedAtMs = nowMs
            cachedRevision = latestRevision
            return cachedRules
        }
    }
}
