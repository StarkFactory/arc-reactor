package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.prompt.PromptVersion
import com.arc.reactor.prompt.VersionStatus
import com.arc.reactor.promptlab.ExperimentOrchestrator
import com.arc.reactor.promptlab.ExperimentStore
import com.arc.reactor.promptlab.PromptLabProperties
import com.arc.reactor.promptlab.analysis.FeedbackAnalyzer
import com.arc.reactor.promptlab.model.Experiment
import com.arc.reactor.promptlab.model.ExperimentReport
import com.arc.reactor.promptlab.model.ExperimentStatus
import com.arc.reactor.promptlab.model.FeedbackAnalysis
import com.arc.reactor.promptlab.model.Recommendation
import com.arc.reactor.promptlab.model.RecommendationConfidence
import com.arc.reactor.promptlab.model.TestQuery
import com.arc.reactor.promptlab.model.Trial
import com.arc.reactor.promptlab.model.VersionSummary
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

class PromptLabControllerTest {

    private val experimentStore: ExperimentStore = mockk(relaxed = true)
    private val orchestrator: ExperimentOrchestrator = mockk(relaxed = true)
    private val feedbackAnalyzer: FeedbackAnalyzer = mockk()
    private val promptTemplateStore: PromptTemplateStore = mockk()
    private lateinit var controller: PromptLabController

    private val now = Instant.parse("2026-01-01T12:00:00Z")

    @BeforeEach
    fun setUp() {
        controller = PromptLabController(
            experimentStore, orchestrator, feedbackAnalyzer, promptTemplateStore,
            PromptLabProperties()
        )
    }

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attrs = mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN,
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to "admin-user"
        )
        every { exchange.attributes } returns attrs
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attrs = mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER
        )
        every { exchange.attributes } returns attrs
        return exchange
    }

    @Nested
    inner class ExperimentCrud {

        @Test
        fun `POST should create experiment`() {
            every { experimentStore.save(any()) } answers { firstArg() }
            val request = CreateExperimentRequest(
                name = "Test Experiment",
                templateId = "tmpl-1",
                baselineVersionId = "v-1",
                candidateVersionIds = listOf("v-2"),
                testQueries = listOf(
                    TestQueryRequest(query = "What is AI?", intent = "search")
                )
            )

            val response = controller.createExperiment(request, adminExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should return 201" }
        }

        @Test
        fun `POST should reject non-admin users`() {
            val request = CreateExperimentRequest(
                name = "Test",
                templateId = "tmpl-1",
                baselineVersionId = "v-1",
                candidateVersionIds = listOf("v-2"),
                testQueries = listOf(TestQueryRequest(query = "Q"))
            )

            val response = controller.createExperiment(request, userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403" }
        }

        @Test
        fun `GET should list experiments`() {
            every { experimentStore.list(null, null) } returns listOf(buildExperiment())

            val response = controller.listExperiments(null, null, adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
        }

        @Test
        fun `GET {id} should return experiment details`() {
            every { experimentStore.get("exp-1") } returns buildExperiment()

            val response = controller.getExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
        }

        @Test
        fun `GET {id} should return 404 for unknown experiment`() {
            every { experimentStore.get("unknown") } returns null

            val response = controller.getExperiment("unknown", adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `DELETE should remove experiment`() {
            val response = controller.deleteExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "Should return 204" }
            verify { experimentStore.delete("exp-1") }
        }
    }

    @Nested
    inner class ExperimentExecution {

        @Test
        fun `POST run should accept PENDING experiment`() {
            every { experimentStore.get("exp-1") } returns buildExperiment(
                status = ExperimentStatus.PENDING
            )

            val response = controller.runExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.ACCEPTED, response.statusCode) { "Should return 202" }
        }

        @Test
        fun `POST run should reject non-PENDING experiment`() {
            every { experimentStore.get("exp-1") } returns buildExperiment(
                status = ExperimentStatus.COMPLETED
            )

            val response = controller.runExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
        }

        @Test
        fun `POST cancel should cancel RUNNING experiment`() {
            every { experimentStore.get("exp-1") } returns buildExperiment(
                status = ExperimentStatus.RUNNING
            )
            every { experimentStore.save(any()) } answers { firstArg() }

            val response = controller.cancelExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
        }

        @Test
        fun `POST cancel should reject non-RUNNING experiment`() {
            every { experimentStore.get("exp-1") } returns buildExperiment(
                status = ExperimentStatus.PENDING
            )

            val response = controller.cancelExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
        }

        @Test
        fun `GET status should return experiment status`() {
            every { experimentStore.get("exp-1") } returns buildExperiment(
                status = ExperimentStatus.COMPLETED
            )

            val response = controller.getStatus("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            val body = response.body as ExperimentStatusResponse
            assertEquals("COMPLETED", body.status) { "Status should be COMPLETED" }
        }

        @Test
        fun `GET status should reject non-admin users`() {
            val response = controller.getStatus("exp-1", userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403" }
        }
    }

    @Nested
    inner class ReportAndTrials {

        @Test
        fun `GET trials should return trial data`() {
            every { experimentStore.getTrials("exp-1") } returns listOf(buildTrial())

            val response = controller.getTrials("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
        }

        @Test
        fun `GET report should return report data`() {
            every { experimentStore.getReport("exp-1") } returns buildReport()

            val response = controller.getReport("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            assertNotNull(response.body) { "Body should not be null" }
        }

        @Test
        fun `GET report should return 404 when no report`() {
            every { experimentStore.getReport("exp-1") } returns null

            val response = controller.getReport("exp-1", adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }
    }

    @Nested
    inner class Automation {

        @Test
        fun `POST auto-optimize should start pipeline`() {
            val request = AutoOptimizeRequest(templateId = "tmpl-1")

            val response = controller.autoOptimize(request, adminExchange())

            assertEquals(HttpStatus.ACCEPTED, response.statusCode) { "Should return 202" }
        }

        @Test
        fun `POST analyze should return feedback analysis`() = runTest {
            coEvery { feedbackAnalyzer.analyze("tmpl-1", null, 50) } returns FeedbackAnalysis(
                totalFeedback = 10,
                negativeCount = 5,
                weaknesses = emptyList(),
                sampleQueries = emptyList()
            )

            val request = AnalyzeFeedbackRequest(templateId = "tmpl-1")
            val response = controller.analyzeFeedback(request, adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            val body = response.body as FeedbackAnalysisResponse
            assertEquals(5, body.negativeCount) { "Negative count should be 5" }
        }
    }

    @Nested
    inner class ActivateRecommended {

        @Test
        fun `POST activate should activate recommended version`() {
            every { experimentStore.get("exp-1") } returns buildExperiment()
            every { experimentStore.getReport("exp-1") } returns buildReport()
            every {
                promptTemplateStore.activateVersion("tmpl-1", "best-v")
            } returns PromptVersion(
                id = "best-v", templateId = "tmpl-1", version = 2,
                content = "Improved prompt", status = VersionStatus.ACTIVE
            )

            val response = controller.activateRecommended("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
        }

        @Test
        fun `POST activate should return 404 for unknown experiment`() {
            every { experimentStore.get("unknown") } returns null

            val response = controller.activateRecommended("unknown", adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `POST activate should return 400 when no report available`() {
            every { experimentStore.get("exp-1") } returns buildExperiment()
            every { experimentStore.getReport("exp-1") } returns null

            val response = controller.activateRecommended("exp-1", adminExchange())

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
        }
    }

    // ── Helpers ──

    private fun buildExperiment(
        status: ExperimentStatus = ExperimentStatus.PENDING
    ): Experiment {
        return Experiment(
            id = "exp-1",
            name = "Test",
            templateId = "tmpl-1",
            baselineVersionId = "v-1",
            candidateVersionIds = listOf("v-2"),
            testQueries = listOf(TestQuery(query = "Q")),
            status = status,
            createdAt = now
        )
    }

    private fun buildTrial(): Trial {
        return Trial(
            experimentId = "exp-1",
            promptVersionId = "v-1",
            promptVersionNumber = 1,
            testQuery = TestQuery(query = "Q"),
            response = "Answer",
            success = true,
            executedAt = now
        )
    }

    private fun buildReport(): ExperimentReport {
        return ExperimentReport(
            experimentId = "exp-1",
            experimentName = "Test",
            totalTrials = 2,
            versionSummaries = listOf(
                VersionSummary(
                    versionId = "v-1", versionNumber = 1, isBaseline = true,
                    totalTrials = 1, passCount = 1, passRate = 1.0,
                    avgScore = 0.9, avgDurationMs = 100.0, totalTokens = 150,
                    tierBreakdown = emptyMap(), toolUsageFrequency = emptyMap(),
                    errorRate = 0.0
                )
            ),
            queryComparisons = emptyList(),
            recommendation = Recommendation(
                bestVersionId = "best-v",
                bestVersionNumber = 2,
                confidence = RecommendationConfidence.HIGH,
                reasoning = "Better pass rate"
            )
        )
    }
}
