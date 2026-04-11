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
import java.util.concurrent.CountDownLatch

/**
 * PromptLabController에 대한 테스트.
 *
 * 프롬프트 실험실 REST API의 동작을 검증합니다.
 */
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
        fun `POST은(는) create experiment해야 한다`() {
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
        fun `POST은(는) reject non-admin users해야 한다`() {
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
        fun `GET은(는) list experiments해야 한다`() {
            every { experimentStore.list(null, null) } returns listOf(buildExperiment())

            val response = controller.listExperiments(null, null, adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
        }

        @Test
        fun `GET {id}은(는) return experiment details해야 한다`() {
            every { experimentStore.get("exp-1") } returns buildExperiment()

            val response = controller.getExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
        }

        @Test
        fun `GET {id}은(는) return 404 for unknown experiment해야 한다`() {
            every { experimentStore.get("unknown") } returns null

            val response = controller.getExperiment("unknown", adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `DELETE은(는) remove experiment해야 한다`() {
            val response = controller.deleteExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "Should return 204" }
            verify { experimentStore.delete("exp-1") }
        }
    }

    @Nested
    inner class ExperimentExecution {

        @Test
        fun `POST run은(는) accept PENDING experiment해야 한다`() {
            every { experimentStore.get("exp-1") } returns buildExperiment(
                status = ExperimentStatus.PENDING
            )

            val response = controller.runExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.ACCEPTED, response.statusCode) { "Should return 202" }
        }

        @Test
        fun `POST run은(는) reject non-PENDING experiment해야 한다`() {
            every { experimentStore.get("exp-1") } returns buildExperiment(
                status = ExperimentStatus.COMPLETED
            )

            val response = controller.runExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
        }

        @Test
        fun `POST cancel은(는) cancel RUNNING experiment해야 한다`() {
            every { experimentStore.get("exp-1") } returns buildExperiment(
                status = ExperimentStatus.RUNNING
            )
            every { experimentStore.save(any()) } answers { firstArg() }

            val response = controller.cancelExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
        }

        @Test
        fun `POST cancel은(는) reject non-RUNNING experiment해야 한다`() {
            every { experimentStore.get("exp-1") } returns buildExperiment(
                status = ExperimentStatus.PENDING
            )

            val response = controller.cancelExperiment("exp-1", adminExchange())

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
        }

        @Test
        fun `GET status은(는) return experiment status해야 한다`() {
            every { experimentStore.get("exp-1") } returns buildExperiment(
                status = ExperimentStatus.COMPLETED
            )

            val response = controller.getStatus("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            val body = response.body as ExperimentStatusResponse
            assertEquals("COMPLETED", body.status) { "Status should be COMPLETED" }
        }

        @Test
        fun `GET status은(는) reject non-admin users해야 한다`() {
            val response = controller.getStatus("exp-1", userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403" }
        }
    }

    @Nested
    inner class ReportAndTrials {

        @Test
        fun `GET trials은(는) return trial data해야 한다`() {
            every { experimentStore.getTrials("exp-1") } returns listOf(buildTrial())

            val response = controller.getTrials("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
        }

        @Test
        fun `GET report은(는) return report data해야 한다`() {
            every { experimentStore.getReport("exp-1") } returns buildReport()

            val response = controller.getReport("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            assertNotNull(response.body) { "Body should not be null" }
        }

        @Test
        fun `GET report은(는) return 404 when no report해야 한다`() {
            every { experimentStore.getReport("exp-1") } returns null

            val response = controller.getReport("exp-1", adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }
    }

    @Nested
    inner class Automation {

        @Test
        fun `POST auto-optimize은(는) start pipeline해야 한다`() {
            val request = AutoOptimizeRequest(templateId = "tmpl-1")

            val response = controller.autoOptimize(request, adminExchange())

            assertEquals(HttpStatus.ACCEPTED, response.statusCode) { "Should return 202" }
        }

        @Test
        fun `POST auto-optimize은(는) return 429 when max concurrent experiments reached해야 한다`() {
            val latch = CountDownLatch(1)
            val blockingOrchestrator: ExperimentOrchestrator = mockk(relaxed = true)
            coEvery { blockingOrchestrator.runAutoPipeline(any(), any(), any(), any()) } coAnswers {
                latch.await()
                null
            }
            val limitedController = PromptLabController(
                experimentStore, blockingOrchestrator, feedbackAnalyzer, promptTemplateStore,
                PromptLabProperties(maxConcurrentExperiments = 1)
            )
            // the single slot를 채웁니다 — orchestrator가 latch에서 블로킹되므로 job이 유지됩니다
            limitedController.autoOptimize(AutoOptimizeRequest(templateId = "tmpl-1"), adminExchange())

            val response = try {
                limitedController.autoOptimize(
                    AutoOptimizeRequest(templateId = "tmpl-2"), adminExchange()
                )
            } finally {
                latch.countDown()
            }

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode) { "Should return 429" }
        }

        @Test
        fun `POST run은(는) return 429 when max concurrent experiments reached해야 한다`() {
            val latch = CountDownLatch(1)
            val blockingOrchestrator: ExperimentOrchestrator = mockk(relaxed = true)
            coEvery { blockingOrchestrator.execute(any()) } coAnswers {
                latch.await()
                buildExperiment()
            }
            val limitedController = PromptLabController(
                experimentStore, blockingOrchestrator, feedbackAnalyzer, promptTemplateStore,
                PromptLabProperties(maxConcurrentExperiments = 1)
            )
            every { experimentStore.get(any()) } returns buildExperiment(status = ExperimentStatus.PENDING)
            // the single slot를 채웁니다 — orchestrator가 latch에서 블로킹되므로 job이 유지됩니다
            limitedController.runExperiment("exp-1", adminExchange())

            val response = try {
                limitedController.runExperiment("exp-2", adminExchange())
            } finally {
                latch.countDown()
            }

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode) { "Should return 429" }
        }

        @Test
        fun `POST analyze은(는) return feedback analysis해야 한다`() = runTest {
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
        fun `POST activate은(는) activate recommended version해야 한다`() {
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
        fun `POST activate은(는) return 404 for unknown experiment해야 한다`() {
            every { experimentStore.get("unknown") } returns null

            val response = controller.activateRecommended("unknown", adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `POST activate은(는) return 400 when no report available해야 한다`() {
            every { experimentStore.get("exp-1") } returns buildExperiment()
            every { experimentStore.getReport("exp-1") } returns null

            val response = controller.activateRecommended("exp-1", adminExchange())

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) { "Should return 400" }
        }
    }

    @Nested
    inner class R299_ErrorMessageSanitization {

        @Test
        fun `R299 sanitize null returns null`() {
            assertEquals(null, controller.sanitizeExperimentError(null)) {
                "R299 fix: null 입력은 null 반환"
            }
        }

        @Test
        fun `R299 sanitize blank returns null`() {
            assertEquals(null, controller.sanitizeExperimentError("   ")) {
                "R299 fix: blank 입력은 null 반환"
            }
        }

        @Test
        fun `R299 sanitize strips stack trace lines`() {
            // 다중 라인 입력 — 첫 라인만 살아남아야 함 (stack trace 차단)
            val raw = """
                LLM provider error: connection refused
                    at com.arc.reactor.llm.GeminiClient.invoke(GeminiClient.kt:42)
                    at com.arc.reactor.agent.SpringAiAgentExecutor.execute(SpringAiAgentExecutor.kt:128)
                Caused by: java.net.ConnectException
            """.trimIndent()

            val sanitized = controller.sanitizeExperimentError(raw)

            assertNotNull(sanitized) { "non-blank 입력은 null 아니어야 한다" }
            assertEquals("LLM provider error: connection refused", sanitized) {
                "R299 fix: 첫 번째 non-blank 라인만 추출되어야 한다 (stack trace 라인 제거)"
            }
        }

        @Test
        fun `R299 sanitize truncates long messages with ellipsis`() {
            // 200자 초과 → truncation + ... suffix
            val longMessage = "Database query failed: " + "x".repeat(300)
            val sanitized = controller.sanitizeExperimentError(longMessage)

            assertNotNull(sanitized)
            assertEquals(
                PromptLabController.MAX_ERROR_MESSAGE_LENGTH + 3,
                sanitized!!.length
            ) {
                "R299 fix: 200자 초과 시 truncate + '...' suffix (총 203자)"
            }
            assertEquals(true, sanitized.endsWith("...")) {
                "R299 fix: truncated suffix '...' 포함"
            }
        }

        @Test
        fun `R299 sanitize keeps short messages intact`() {
            val shortMessage = "Experiment timed out after 60s"
            val sanitized = controller.sanitizeExperimentError(shortMessage)

            assertEquals(shortMessage, sanitized) {
                "R299 fix: 200자 이하 단일 라인은 그대로 유지"
            }
        }

        @Test
        fun `R299 getStatus 응답 errorMessage는 sanitize되어야 한다`() {
            // R299 fix 검증: getStatus 응답에서 raw stack trace가 노출되지 않음
            val sensitive = """
                JDBC error at /var/lib/db/sensitive.sql line 42: secret column not found
                    at org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse
                    at org.postgresql.jdbc.PgStatement.execute
            """.trimIndent()

            val experiment = buildExperiment().copy(
                status = ExperimentStatus.FAILED,
                errorMessage = sensitive
            )
            every { experimentStore.get("exp-1") } returns experiment

            val response = controller.getStatus("exp-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode)
            val body = response.body as ExperimentStatusResponse
            assertEquals(
                "JDBC error at /var/lib/db/sensitive.sql line 42: secret column not found",
                body.errorMessage
            ) {
                "R299 fix: errorMessage는 첫 라인만 포함하고 stack trace는 제거"
            }
            // stack trace 라인이 없음을 명시 검증
            assertEquals(false, body.errorMessage?.contains("at org.postgresql")) {
                "R299 fix: stack trace 라인은 응답에 포함되면 안 된다"
            }
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
