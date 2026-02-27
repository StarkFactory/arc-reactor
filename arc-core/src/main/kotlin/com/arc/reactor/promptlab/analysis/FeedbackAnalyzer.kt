package com.arc.reactor.promptlab.analysis

import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.feedback.Feedback
import com.arc.reactor.feedback.FeedbackRating
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.promptlab.model.FeedbackAnalysis
import com.arc.reactor.promptlab.model.PromptWeakness
import com.arc.reactor.promptlab.model.TestQuery
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Analyzes negative user feedback to identify prompt weaknesses.
 *
 * Uses LLM to classify weakness patterns from thumbs-down feedback,
 * producing a [FeedbackAnalysis] that drives candidate prompt generation.
 */
class FeedbackAnalyzer(
    private val feedbackStore: FeedbackStore,
    private val chatModelProvider: ChatModelProvider
) {

    private val mapper = jacksonObjectMapper()

    /**
     * Analyze negative feedback for a template and identify weaknesses.
     *
     * Filters feedback by templateId when available. Feedback entries
     * without a templateId are excluded when filtering by template.
     *
     * @param templateId the prompt template to analyze feedback for
     * @param since only consider feedback after this timestamp
     * @param maxSamples maximum number of feedback entries to analyze
     * @return analysis result with identified weaknesses and sample queries
     */
    suspend fun analyze(
        templateId: String,
        since: Instant? = null,
        maxSamples: Int = 50
    ): FeedbackAnalysis {
        val allFeedbackCount = countAllFeedback(since, templateId)
        val negativeFeedback = fetchNegativeFeedback(since, maxSamples, templateId)
        if (negativeFeedback.isEmpty()) {
            logger.info { "No negative feedback found for template=$templateId" }
            return emptyAnalysis()
        }
        val weaknesses = classifyWeaknesses(negativeFeedback)
        val sampleQueries = extractTestQueries(negativeFeedback)
        logger.info {
            "Analyzed ${negativeFeedback.size} negative feedback for " +
                "template=$templateId, found ${weaknesses.size} categories"
        }
        return FeedbackAnalysis(
            totalFeedback = allFeedbackCount,
            negativeCount = negativeFeedback.size,
            weaknesses = weaknesses,
            sampleQueries = sampleQueries
        )
    }

    private fun countAllFeedback(since: Instant?, templateId: String): Int {
        return feedbackStore.list(from = since, templateId = templateId).size
    }

    private fun fetchNegativeFeedback(
        since: Instant?,
        maxSamples: Int,
        templateId: String
    ): List<Feedback> {
        return feedbackStore.list(
            rating = FeedbackRating.THUMBS_DOWN,
            from = since,
            templateId = templateId
        ).take(maxSamples)
    }

    private suspend fun classifyWeaknesses(feedback: List<Feedback>): List<PromptWeakness> {
        val prompt = buildAnalysisPrompt(feedback)
        val result = callLlm(prompt)
        return parseWeaknessResponse(result)
    }

    private fun buildAnalysisPrompt(feedback: List<Feedback>): String {
        val entries = feedback.joinToString("\n---\n") { fb ->
            "Query: ${fb.query}\nResponse: ${fb.response}\nComment: ${fb.comment.orEmpty()}"
        }
        return """Analyze the following negative user feedback entries and classify the weaknesses.
            |
            |$entries
            |
            |Respond with JSON only:
            |{"weaknesses": [{"category": "...", "description": "...", "frequency": N, "exampleQueries": ["..."]}]}
            |
            |Categories: short_answer, missing_sources, incorrect_info, no_tool_usage, missing_context, off_topic, poor_formatting, other""".trimMargin()
    }

    private suspend fun callLlm(prompt: String): String {
        try {
            val client = chatModelProvider.getChatClient(null)
            return client.prompt().user(prompt).call().content().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "LLM call failed during feedback analysis" }
            return ""
        }
    }

    private fun parseWeaknessResponse(
        response: String
    ): List<PromptWeakness> {
        if (response.isBlank()) return emptyList()
        return try {
            val cleaned = response.replace(CODE_FENCE_REGEX, "").trim()
            val tree = mapper.readTree(cleaned)
            val weaknesses = tree["weaknesses"] ?: return emptyList()
            weaknesses.map { node ->
                PromptWeakness(
                    category = node["category"]?.asText().orEmpty(),
                    description = node["description"]?.asText().orEmpty(),
                    frequency = node["frequency"]?.asInt() ?: 0,
                    exampleQueries = node["exampleQueries"]
                        ?.map { it.asText() } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse LLM weakness response" }
            emptyList()
        }
    }

    private fun extractTestQueries(feedback: List<Feedback>): List<TestQuery> {
        return feedback.map { fb ->
            TestQuery(
                query = fb.query,
                intent = fb.intent,
                domain = fb.domain
            )
        }
    }

    private fun emptyAnalysis() = FeedbackAnalysis(
        totalFeedback = 0,
        negativeCount = 0,
        weaknesses = emptyList(),
        sampleQueries = emptyList()
    )

    companion object {
        private val CODE_FENCE_REGEX = Regex("```json\\s*|```\\s*")
    }
}
