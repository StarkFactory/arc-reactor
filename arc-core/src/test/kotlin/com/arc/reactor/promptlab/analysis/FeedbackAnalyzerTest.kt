package com.arc.reactor.promptlab.analysis

import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.feedback.Feedback
import com.arc.reactor.feedback.FeedbackRating
import com.arc.reactor.feedback.FeedbackStore
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import java.time.Instant

/**
 * FeedbackAnalyzer에 대한 테스트.
 *
 * 피드백 분석 로직을 검증합니다.
 */
class FeedbackAnalyzerTest {

    private val feedbackStore: FeedbackStore = mockk()
    private val chatModelProvider: ChatModelProvider = mockk()
    private val chatClient: ChatClient = mockk()
    private val requestSpec: ChatClient.ChatClientRequestSpec = mockk(relaxed = true)
    private val callResponseSpec: ChatClient.CallResponseSpec = mockk()

    private lateinit var analyzer: FeedbackAnalyzer

    @BeforeEach
    fun setup() {
        analyzer = FeedbackAnalyzer(feedbackStore, chatModelProvider)
        every { chatModelProvider.getChatClient(any()) } returns chatClient
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns callResponseSpec
    }

    private fun createFeedback(
        id: String = "fb-1",
        query: String = "How do I reset my password?",
        response: String = "Try clicking the button.",
        comment: String? = "Too short",
        intent: String? = "account",
        domain: String? = "support"
    ) = Feedback(
        feedbackId = id,
        query = query,
        response = response,
        rating = FeedbackRating.THUMBS_DOWN,
        comment = comment,
        intent = intent,
        domain = domain
    )

    @Nested
    inner class AnalyzeWithFeedback {

        @Test
        fun `analyze negative feedback and return weaknesses해야 한다`() = runTest {
            val feedback = listOf(
                createFeedback(id = "fb-1", comment = "Too short"),
                createFeedback(id = "fb-2", query = "Where are the docs?", comment = "No sources")
            )
            every { feedbackStore.list(from = null, templateId = "template-1") } returns feedback + feedback
            every { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = null, templateId = "template-1") } returns feedback

            val llmResponse = """
                {"weaknesses": [
                    {"category": "short_answer", "description": "Responses are too brief", "frequency": 2, "exampleQueries": ["How do I reset my password?"]},
                    {"category": "missing_sources", "description": "No references provided", "frequency": 1, "exampleQueries": ["Where are the docs?"]}
                ]}
            """.trimIndent()
            every { callResponseSpec.content() } returns llmResponse

            val result = analyzer.analyze("template-1")

            result.totalFeedback shouldBe 4 // "Should count all feedback"
            result.negativeCount shouldBe 2 // "Should count negative feedback"
            result.weaknesses shouldHaveSize 2 // "Should identify 2 weakness categories"
            result.weaknesses[0].category shouldBe "short_answer"  // "First weakness은(는) be short_answer"해야 합니다
            result.weaknesses[1].category shouldBe "missing_sources"  // "Second weakness은(는) be missing_sources"해야 합니다
            result.weaknesses[0].frequency shouldBe 2  // "Frequency은(는) match"해야 합니다
        }

        @Test
        fun `extract test queries from feedback해야 한다`() = runTest {
            val feedback = listOf(
                createFeedback(id = "fb-1", query = "Reset password", intent = "account", domain = "support"),
                createFeedback(id = "fb-2", query = "Find docs", intent = "docs", domain = "knowledge")
            )
            every { feedbackStore.list(from = null, templateId = "template-1") } returns feedback
            every { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = null, templateId = "template-1") } returns feedback
            every { callResponseSpec.content() } returns """{"weaknesses": []}"""

            val result = analyzer.analyze("template-1")

            result.sampleQueries shouldHaveSize 2 // "Should extract 2 test queries"
            result.sampleQueries[0].query shouldBe "Reset password"  // "First query text은(는) match"해야 합니다
            result.sampleQueries[0].intent shouldBe "account"  // "Intent은(는) be preserved"해야 합니다
            result.sampleQueries[0].domain shouldBe "support"  // "Domain은(는) be preserved"해야 합니다
        }

        @Test
        fun `limit feedback to maxSamples해야 한다`() = runTest {
            val feedback = (1..100).map { createFeedback(id = "fb-$it") }
            every { feedbackStore.list(from = null, templateId = "template-1") } returns feedback
            every { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = null, templateId = "template-1") } returns feedback
            every { callResponseSpec.content() } returns """{"weaknesses": []}"""

            val result = analyzer.analyze("template-1", maxSamples = 10)

            result.totalFeedback shouldBe 100 // "Should count all feedback"
            result.negativeCount shouldBe 10 // "Should limit negative to maxSamples"
            result.sampleQueries shouldHaveSize 10  // "Queries은(는) also be limited"해야 합니다
        }

        @Test
        fun `pass since parameter to feedbackStore해야 한다`() = runTest {
            val since = Instant.parse("2026-01-01T00:00:00Z")
            every { feedbackStore.list(from = since, templateId = "template-1") } returns emptyList()
            every { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = since, templateId = "template-1") } returns emptyList()

            analyzer.analyze("template-1", since = since)

            verify { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = since, templateId = "template-1") }
        }
    }

    @Nested
    inner class NoNegativeFeedback {

        @Test
        fun `no negative feedback exists일 때 return empty analysis해야 한다`() = runTest {
            every { feedbackStore.list(from = null, templateId = "template-1") } returns emptyList()
            every { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = null, templateId = "template-1") } returns emptyList()

            val result = analyzer.analyze("template-1")

            result.totalFeedback shouldBe 0  // "Total은(는) be 0"해야 합니다
            result.negativeCount shouldBe 0  // "Negative count은(는) be 0"해야 합니다
            result.weaknesses.shouldBeEmpty() // "No weaknesses expected"
            result.sampleQueries.shouldBeEmpty() // "No sample queries expected"
        }

        @Test
        fun `no negative feedback일 때 not call LLM해야 한다`() = runTest {
            every { feedbackStore.list(from = null, templateId = "template-1") } returns emptyList()
            every { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = null, templateId = "template-1") } returns emptyList()

            analyzer.analyze("template-1")

            verify(exactly = 0) { chatClient.prompt() }
        }
    }

    @Nested
    inner class LlmErrorHandling {

        @Test
        fun `LLM returns invalid JSON일 때 return empty weaknesses해야 한다`() = runTest {
            every { feedbackStore.list(from = null, templateId = "template-1") } returns listOf(createFeedback())
            every { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = null, templateId = "template-1") } returns listOf(createFeedback())
            every { callResponseSpec.content() } returns "not valid json at all"

            val result = analyzer.analyze("template-1")

            result.weaknesses.shouldBeEmpty()  // "Invalid JSON은(는) result in empty weaknesses"해야 합니다
            result.sampleQueries shouldHaveSize 1  // "Queries은(는) still be extracted"해야 합니다
        }

        @Test
        fun `LLM returns empty response일 때 return empty weaknesses해야 한다`() = runTest {
            every { feedbackStore.list(from = null, templateId = "template-1") } returns listOf(createFeedback())
            every { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = null, templateId = "template-1") } returns listOf(createFeedback())
            every { callResponseSpec.content() } returns ""

            val result = analyzer.analyze("template-1")

            result.weaknesses.shouldBeEmpty()  // "Empty response은(는) result in empty weaknesses"해야 합니다
        }

        @Test
        fun `LLM returns null content일 때 return empty weaknesses해야 한다`() = runTest {
            every { feedbackStore.list(from = null, templateId = "template-1") } returns listOf(createFeedback())
            every { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = null, templateId = "template-1") } returns listOf(createFeedback())
            every { callResponseSpec.content() } returns null

            val result = analyzer.analyze("template-1")

            result.weaknesses.shouldBeEmpty()  // "Null content은(는) result in empty weaknesses"해야 합니다
        }

        @Test
        fun `handle LLM response wrapped in code fences해야 한다`() = runTest {
            every { feedbackStore.list(from = null, templateId = "template-1") } returns listOf(createFeedback())
            every { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = null, templateId = "template-1") } returns listOf(createFeedback())
            val wrappedResponse = """
                ```json
                {"weaknesses": [{"category": "short_answer", "description": "Too brief", "frequency": 1, "exampleQueries": []}]}
                ```
            """.trimIndent()
            every { callResponseSpec.content() } returns wrappedResponse

            val result = analyzer.analyze("template-1")

            result.weaknesses shouldHaveSize 1 // "Should parse JSON inside code fences"
            result.weaknesses[0].category shouldBe "short_answer"  // "Category은(는) match"해야 합니다
        }

        @Test
        fun `handle LLM call exception gracefully해야 한다`() = runTest {
            every { feedbackStore.list(from = null, templateId = "template-1") } returns listOf(createFeedback())
            every { feedbackStore.list(rating = FeedbackRating.THUMBS_DOWN, from = null, templateId = "template-1") } returns listOf(createFeedback())
            every { callResponseSpec.content() } throws RuntimeException("API error")

            val result = analyzer.analyze("template-1")

            result.weaknesses.shouldBeEmpty()  // "Exception은(는) result in empty weaknesses"해야 합니다
            result.sampleQueries shouldHaveSize 1  // "Queries은(는) still be extracted"해야 합니다
        }
    }
}
