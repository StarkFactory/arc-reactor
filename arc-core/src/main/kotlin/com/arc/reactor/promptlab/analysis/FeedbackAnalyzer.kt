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
 * 부정 사용자 피드백을 분석하여 프롬프트 약점을 식별한다.
 *
 * LLM을 사용하여 싫어요(thumbs-down) 피드백에서 약점 패턴을 분류하고,
 * 후보 프롬프트 생성을 구동하는 [FeedbackAnalysis]를 생성한다.
 *
 * WHY: 부정 피드백의 패턴을 자동으로 분류하여
 * PromptCandidateGenerator가 약점을 보완하는 프롬프트를 생성할 수 있게 한다.
 *
 * @see com.arc.reactor.promptlab.analysis.PromptCandidateGenerator 후보 생성기
 * @see ExperimentOrchestrator 자동 파이프라인
 */
class FeedbackAnalyzer(
    private val feedbackStore: FeedbackStore,
    private val chatModelProvider: ChatModelProvider
) {

    private val objectMapper = jacksonObjectMapper()

    /**
     * 템플릿에 대한 부정 피드백을 분석하고 약점을 식별한다.
     *
     * templateId가 있는 피드백만 필터링한다.
     * templateId가 없는 피드백 항목은 템플릿 기준 필터링에서 제외된다.
     *
     * @param templateId 피드백을 분석할 프롬프트 템플릿 ID
     * @param since 이 시각 이후의 피드백만 분석 대상으로 포함
     * @param maxSamples 분석할 최대 피드백 항목 수
     * @return 식별된 약점과 샘플 쿼리가 포함된 분석 결과
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
            val tree = objectMapper.readTree(cleaned)
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
