package com.arc.reactor.promptlab.eval

import com.arc.reactor.promptlab.model.EvaluationResult
import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.TestQuery
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Tier 1: Structural evaluation.
 *
 * Validates that the response has valid JSON structure with required fields.
 * Plain text responses receive a compatibility score.
 */
class StructuralEvaluator : PromptEvaluator {

    private val mapper = jacksonObjectMapper()

    override suspend fun evaluate(
        response: String,
        query: TestQuery
    ): EvaluationResult {
        val json = parseJson(response)
            ?: return plainTextResult()

        val type = json["type"]?.toString()
        val hasMessage = json.containsKey("message")
        val hasSummary = json.containsKey("summary")

        if (type == null || type !in VALID_TYPES) {
            logger.debug { "Invalid or missing type: $type" }
            return missingFieldResult("Missing or invalid 'type' field")
        }

        val hasContent = if (type == "briefing") hasSummary else hasMessage
        if (!hasContent) {
            val field = if (type == "briefing") "summary" else "message"
            logger.debug { "Missing '$field' for type=$type" }
            return missingFieldResult("Missing '$field' field for type=$type")
        }

        return EvaluationResult(
            tier = EvaluationTier.STRUCTURAL,
            passed = true,
            score = 1.0,
            reason = "Valid JSON with type=$type and required fields"
        )
    }

    private fun parseJson(response: String): Map<*, *>? {
        return try {
            mapper.readValue(response.trim(), Map::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun plainTextResult() = EvaluationResult(
        tier = EvaluationTier.STRUCTURAL,
        passed = true,
        score = 0.5,
        reason = "Plain text response (non-JSON)"
    )

    private fun missingFieldResult(reason: String) = EvaluationResult(
        tier = EvaluationTier.STRUCTURAL,
        passed = false,
        score = 0.3,
        reason = reason
    )

    companion object {
        private val VALID_TYPES = setOf(
            "answer", "error", "action",
            "briefing", "clarification", "search"
        )
    }
}
