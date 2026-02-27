package com.arc.reactor.guard.impl

import com.arc.reactor.guard.ClassificationStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Rule-Based Classification Stage
 *
 * Keyword-based content classification for blocking known harmful categories.
 * Fast, zero-LLM-cost first line of defense for content classification.
 */
class RuleBasedClassificationStage(
    private val blockedCategories: Set<String> = DEFAULT_BLOCKED_CATEGORIES,
    customRules: List<ClassificationRule> = emptyList()
) : ClassificationStage {

    override val stageName = "Classification"

    private val allRules: List<ClassificationRule> = DEFAULT_RULES + customRules

    override suspend fun check(command: GuardCommand): GuardResult {
        val text = command.text.lowercase()

        for (rule in allRules) {
            if (rule.category !in blockedCategories) continue

            val matchCount = rule.keywords.count { keyword -> text.contains(keyword) }
            if (matchCount >= rule.minMatchCount) {
                logger.warn {
                    "Classification blocked: category=${rule.category} " +
                        "matches=$matchCount threshold=${rule.minMatchCount}"
                }
                return GuardResult.Rejected(
                    reason = "Content classified as ${rule.category}",
                    category = RejectionCategory.OFF_TOPIC
                )
            }
        }

        return GuardResult.Allowed.DEFAULT
    }

    companion object {
        val DEFAULT_BLOCKED_CATEGORIES = setOf("malware", "weapons", "self_harm")

        val DEFAULT_RULES = listOf(
            ClassificationRule(
                category = "malware",
                keywords = listOf("write malware", "create virus", "ransomware code", "keylogger",
                    "trojan horse code", "exploit code for"),
                minMatchCount = 1
            ),
            ClassificationRule(
                category = "weapons",
                keywords = listOf("build a bomb", "make explosives", "weapon manufacturing",
                    "synthesize poison", "chemical weapon"),
                minMatchCount = 1
            ),
            ClassificationRule(
                category = "self_harm",
                keywords = listOf("how to hurt myself", "suicide methods", "self-harm techniques"),
                minMatchCount = 1
            )
        )
    }
}

data class ClassificationRule(
    val category: String,
    val keywords: List<String>,
    val minMatchCount: Int = 1
)
