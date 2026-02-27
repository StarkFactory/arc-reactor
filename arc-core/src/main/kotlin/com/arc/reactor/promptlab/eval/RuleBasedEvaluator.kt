package com.arc.reactor.promptlab.eval

import com.arc.reactor.promptlab.model.EvaluationResult
import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.TestQuery
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Tier 2: Rule-based evaluation.
 *
 * Applies deterministic rules to check response quality
 * based on intent and response content.
 */
class RuleBasedEvaluator : PromptEvaluator {

    private val mapper = jacksonObjectMapper()

    override suspend fun evaluate(
        response: String,
        query: TestQuery
    ): EvaluationResult {
        val json = parseJson(response)
        val intent = query.intent?.lowercase().orEmpty()
        val type = json?.get("type")?.toString().orEmpty()
        val message = json?.get("message")?.toString().orEmpty()
        val success = json?.get("success")?.toString()?.toBooleanStrictOrNull()
        val suggestions = json?.get("suggestions")

        val results = mutableListOf<Boolean>()

        checkShortAnswer(intent, type, message, results)
        checkActionConfirmation(intent, success, message, results)
        checkErrorQuality(type, message, suggestions, results)
        checkClarificationOnly(type, message, results)

        if (results.isEmpty()) {
            return EvaluationResult(
                tier = EvaluationTier.RULES,
                passed = true,
                score = 1.0,
                reason = "No applicable rules for this response"
            )
        }

        val passed = results.count { it }
        val score = passed.toDouble() / results.size
        val allPassed = results.all { it }

        logger.debug { "Rules: $passed/${results.size} passed, score=$score" }
        return EvaluationResult(
            tier = EvaluationTier.RULES,
            passed = allPassed,
            score = score,
            reason = "Rules passed: $passed/${results.size}"
        )
    }

    private fun checkShortAnswer(
        intent: String,
        type: String,
        message: String,
        results: MutableList<Boolean>
    ) {
        if (!SEARCH_INTENT.containsMatchIn(intent)) return
        if (type != "answer") return
        results.add(message.length > MIN_ANSWER_LENGTH)
    }

    private fun checkActionConfirmation(
        intent: String,
        success: Boolean?,
        message: String,
        results: MutableList<Boolean>
    ) {
        if (!MUTATION_INTENT.containsMatchIn(intent)) return
        if (success != true) return
        results.add(CONFIRMATION_PATTERN.containsMatchIn(message))
    }

    private fun checkErrorQuality(
        type: String,
        message: String,
        suggestions: Any?,
        results: MutableList<Boolean>
    ) {
        if (type != "error") return
        val hasSuggestions = suggestions is List<*> && suggestions.isNotEmpty()
        results.add(hasSuggestions || message.length > MIN_ERROR_LENGTH)
    }

    private fun checkClarificationOnly(
        type: String,
        message: String,
        results: MutableList<Boolean>
    ) {
        if (type != "clarification") return
        results.add(!QUESTION_ONLY.matches(message.trim()))
    }

    private fun parseJson(response: String): Map<*, *>? {
        return try {
            mapper.readValue(response.trim(), Map::class.java)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MIN_ANSWER_LENGTH = 50
        private const val MIN_ERROR_LENGTH = 20

        private val SEARCH_INTENT = Regex(
            "search|find|lookup|query|retrieve",
            RegexOption.IGNORE_CASE
        )
        private val MUTATION_INTENT = Regex(
            "create|update|delete|remove|add|modify",
            RegexOption.IGNORE_CASE
        )
        private val CONFIRMATION_PATTERN = Regex(
            "success|completed|done|created|updated|deleted|removed",
            RegexOption.IGNORE_CASE
        )
        private val QUESTION_ONLY = Regex("^[^.!]*\\?$")
    }
}
