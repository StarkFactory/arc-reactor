package com.arc.reactor.promptlab

import com.arc.reactor.promptlab.model.*
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

class JdbcExperimentStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcExperimentStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)
        val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))

        // Apply V25 migration schema
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS experiments (
                id                    VARCHAR(36)   PRIMARY KEY,
                name                  VARCHAR(255)  NOT NULL,
                description           TEXT          NOT NULL DEFAULT '',
                template_id           VARCHAR(255)  NOT NULL,
                baseline_version_id   VARCHAR(255)  NOT NULL,
                candidate_version_ids TEXT          NOT NULL,
                test_queries          TEXT          NOT NULL,
                evaluation_config     TEXT          NOT NULL,
                model                 VARCHAR(100),
                judge_model           VARCHAR(100),
                temperature           DOUBLE PRECISION NOT NULL DEFAULT 0.3,
                repetitions           INTEGER       NOT NULL DEFAULT 1,
                auto_generated        BOOLEAN       NOT NULL DEFAULT FALSE,
                status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
                created_by            VARCHAR(255)  NOT NULL DEFAULT 'system',
                created_at            TIMESTAMP     NOT NULL,
                started_at            TIMESTAMP,
                completed_at          TIMESTAMP,
                error_message         TEXT
            )
        """.trimIndent())

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS trials (
                id                    VARCHAR(36)   PRIMARY KEY,
                experiment_id         VARCHAR(36)   NOT NULL,
                prompt_version_id     VARCHAR(255)  NOT NULL,
                prompt_version_number INTEGER       NOT NULL,
                test_query            TEXT          NOT NULL,
                repetition_index      INTEGER       NOT NULL DEFAULT 0,
                response              TEXT,
                success               BOOLEAN       NOT NULL DEFAULT FALSE,
                error_message         TEXT,
                tools_used            TEXT,
                token_usage           TEXT,
                duration_ms           BIGINT        NOT NULL DEFAULT 0,
                evaluations           TEXT,
                executed_at           TIMESTAMP     NOT NULL
            )
        """.trimIndent())

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS experiment_reports (
                experiment_id         VARCHAR(36)   PRIMARY KEY,
                report_data           TEXT          NOT NULL,
                created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        store = JdbcExperimentStore(jdbcTemplate, transactionTemplate)
    }

    private fun createExperiment(
        id: String = "exp-1",
        name: String = "Test Experiment",
        templateId: String = "tpl-1",
        status: ExperimentStatus = ExperimentStatus.PENDING,
        createdAt: Instant = Instant.parse("2026-01-15T12:00:00Z")
    ) = Experiment(
        id = id,
        name = name,
        templateId = templateId,
        baselineVersionId = "v1",
        candidateVersionIds = listOf("v2", "v3"),
        testQueries = listOf(
            TestQuery(query = "What is AI?", intent = "explain", tags = listOf("tech"))
        ),
        evaluationConfig = EvaluationConfig(
            structuralEnabled = true,
            rulesEnabled = true,
            llmJudgeEnabled = false
        ),
        model = "gemini-2.0-flash",
        judgeModel = "gemini-2.0-flash",
        temperature = 0.5,
        repetitions = 2,
        status = status,
        createdBy = "tester",
        createdAt = createdAt
    )

    private fun createTrial(
        id: String = "trial-1",
        experimentId: String = "exp-1",
        promptVersionId: String = "v1"
    ) = Trial(
        id = id,
        experimentId = experimentId,
        promptVersionId = promptVersionId,
        promptVersionNumber = 1,
        testQuery = TestQuery(query = "What is AI?", intent = "explain"),
        response = "AI is artificial intelligence.",
        success = true,
        toolsUsed = listOf("search", "calculator"),
        tokenUsage = TokenUsageSummary(promptTokens = 100, completionTokens = 50),
        durationMs = 250,
        evaluations = listOf(
            EvaluationResult(
                tier = EvaluationTier.STRUCTURAL,
                passed = true,
                score = 1.0,
                reason = "Valid structure"
            ),
            EvaluationResult(
                tier = EvaluationTier.RULES,
                passed = true,
                score = 0.85,
                reason = "Passed rule checks"
            )
        ),
        executedAt = Instant.parse("2026-01-15T12:05:00Z")
    )

    private fun createReport(
        experimentId: String = "exp-1"
    ) = ExperimentReport(
        experimentId = experimentId,
        experimentName = "Test Experiment",
        generatedAt = Instant.parse("2026-01-15T12:10:00Z"),
        totalTrials = 4,
        versionSummaries = listOf(
            VersionSummary(
                versionId = "v1",
                versionNumber = 1,
                isBaseline = true,
                totalTrials = 2,
                passCount = 2,
                passRate = 1.0,
                avgScore = 0.9,
                avgDurationMs = 200.0,
                totalTokens = 500,
                tierBreakdown = mapOf(
                    EvaluationTier.STRUCTURAL to TierStats(2, 0, 1.0, 1.0),
                    EvaluationTier.RULES to TierStats(2, 0, 1.0, 0.85)
                ),
                toolUsageFrequency = mapOf("search" to 2, "calculator" to 1),
                errorRate = 0.0
            )
        ),
        queryComparisons = listOf(
            QueryComparison(
                query = TestQuery(query = "What is AI?"),
                versionResults = listOf(
                    QueryVersionResult(
                        versionId = "v1",
                        versionNumber = 1,
                        response = "AI is artificial intelligence.",
                        passed = true,
                        score = 0.9,
                        durationMs = 200,
                        evaluationDetails = emptyList()
                    )
                )
            )
        ),
        recommendation = Recommendation(
            bestVersionId = "v1",
            bestVersionNumber = 1,
            confidence = RecommendationConfidence.HIGH,
            reasoning = "Baseline performs best",
            improvements = listOf("Consider adding examples"),
            warnings = listOf("Low sample size")
        )
    )

    @Nested
    inner class ExperimentCrud {

        @Test
        fun `should start empty`() {
            store.list().shouldBeEmpty()
        }

        @Test
        fun `should save and retrieve experiment`() {
            val experiment = createExperiment()

            store.save(experiment)
            val retrieved = store.get("exp-1")

            retrieved.shouldNotBeNull()
            retrieved.id shouldBe "exp-1"
            retrieved.name shouldBe "Test Experiment"
            retrieved.templateId shouldBe "tpl-1"
            retrieved.status shouldBe ExperimentStatus.PENDING
            retrieved.baselineVersionId shouldBe "v1"
            retrieved.candidateVersionIds shouldBe listOf("v2", "v3")
            retrieved.model shouldBe "gemini-2.0-flash"
            retrieved.temperature shouldBe 0.5
            retrieved.repetitions shouldBe 2
            retrieved.createdBy shouldBe "tester"
        }

        @Test
        fun `should preserve complex JSON fields through round-trip`() {
            val experiment = createExperiment()
            store.save(experiment)
            val retrieved = store.get("exp-1")

            retrieved.shouldNotBeNull()
            retrieved.testQueries shouldHaveSize 1
            retrieved.testQueries[0].query shouldBe "What is AI?"
            retrieved.testQueries[0].intent shouldBe "explain"
            retrieved.testQueries[0].tags shouldBe listOf("tech")
            retrieved.evaluationConfig.structuralEnabled shouldBe true
            retrieved.evaluationConfig.llmJudgeEnabled shouldBe false
        }

        @Test
        fun `should return null for nonexistent experiment`() {
            store.get("nonexistent").shouldBeNull()
        }

        @Test
        fun `should delete experiment and associated data`() {
            store.save(createExperiment())
            store.saveTrials("exp-1", listOf(createTrial()))
            store.saveReport("exp-1", createReport())

            store.delete("exp-1")

            store.get("exp-1").shouldBeNull()
            store.getTrials("exp-1").shouldBeEmpty()
            store.getReport("exp-1").shouldBeNull()
        }

        @Test
        fun `delete should be idempotent for nonexistent experiment`() {
            assertDoesNotThrow { store.delete("nonexistent") }
        }

        @Test
        fun `should upsert experiment on save with same id`() {
            store.save(createExperiment(name = "First", status = ExperimentStatus.PENDING))
            store.save(createExperiment(name = "Updated", status = ExperimentStatus.RUNNING))

            store.list() shouldHaveSize 1
            val retrieved = store.get("exp-1")
            retrieved.shouldNotBeNull()
            retrieved.name shouldBe "Updated"
            retrieved.status shouldBe ExperimentStatus.RUNNING
        }

        @Test
        fun `should preserve nullable timestamp fields`() {
            val withTimestamps = createExperiment().copy(
                startedAt = Instant.parse("2026-01-15T12:01:00Z"),
                completedAt = Instant.parse("2026-01-15T12:09:00Z"),
                errorMessage = "Something went wrong"
            )
            store.save(withTimestamps)

            val retrieved = store.get("exp-1")
            retrieved.shouldNotBeNull()
            retrieved.startedAt shouldBe Instant.parse("2026-01-15T12:01:00Z")
            retrieved.completedAt shouldBe Instant.parse("2026-01-15T12:09:00Z")
            retrieved.errorMessage shouldBe "Something went wrong"
        }

        @Test
        fun `should handle null optional fields`() {
            val withNulls = createExperiment().copy(
                model = null,
                judgeModel = null,
                startedAt = null,
                completedAt = null,
                errorMessage = null
            )
            store.save(withNulls)

            val retrieved = store.get("exp-1")
            retrieved.shouldNotBeNull()
            retrieved.model.shouldBeNull()
            retrieved.judgeModel.shouldBeNull()
            retrieved.startedAt.shouldBeNull()
            retrieved.completedAt.shouldBeNull()
            retrieved.errorMessage.shouldBeNull()
        }
    }

    @Nested
    inner class ExperimentFiltering {

        private val t1 = Instant.parse("2026-01-01T00:00:00Z")
        private val t2 = Instant.parse("2026-01-02T00:00:00Z")
        private val t3 = Instant.parse("2026-01-03T00:00:00Z")

        @BeforeEach
        fun seedData() {
            store.save(createExperiment(
                id = "exp-1", templateId = "tpl-1",
                status = ExperimentStatus.PENDING, createdAt = t1
            ))
            store.save(createExperiment(
                id = "exp-2", templateId = "tpl-2",
                status = ExperimentStatus.COMPLETED, createdAt = t2
            ))
            store.save(createExperiment(
                id = "exp-3", templateId = "tpl-1",
                status = ExperimentStatus.COMPLETED, createdAt = t3
            ))
        }

        @Test
        fun `should filter by status`() {
            val result = store.list(status = ExperimentStatus.COMPLETED)

            result shouldHaveSize 2
            result.all { it.status == ExperimentStatus.COMPLETED } shouldBe true
        }

        @Test
        fun `should filter by templateId`() {
            val result = store.list(templateId = "tpl-1")

            result shouldHaveSize 2
            result.all { it.templateId == "tpl-1" } shouldBe true
        }

        @Test
        fun `should combine status and templateId filters`() {
            val result = store.list(
                status = ExperimentStatus.COMPLETED,
                templateId = "tpl-1"
            )

            result shouldHaveSize 1
            result[0].id shouldBe "exp-3"
        }

        @Test
        fun `should return all when no filters provided`() {
            store.list() shouldHaveSize 3
        }

        @Test
        fun `should return empty list when no entries match`() {
            store.list(status = ExperimentStatus.FAILED).shouldBeEmpty()
        }

        @Test
        fun `should sort by createdAt descending`() {
            val result = store.list()

            result[0].id shouldBe "exp-3"
            result[1].id shouldBe "exp-2"
            result[2].id shouldBe "exp-1"
        }
    }

    @Nested
    inner class TrialOperations {

        @Test
        fun `should save and retrieve trials`() {
            val trials = listOf(
                createTrial(id = "t-1", promptVersionId = "v1"),
                createTrial(id = "t-2", promptVersionId = "v2")
            )

            store.saveTrials("exp-1", trials)
            val retrieved = store.getTrials("exp-1")

            retrieved shouldHaveSize 2
            retrieved[0].id shouldBe "t-1"
            retrieved[1].id shouldBe "t-2"
        }

        @Test
        fun `should preserve trial JSON fields through round-trip`() {
            store.saveTrials("exp-1", listOf(createTrial()))
            val retrieved = store.getTrials("exp-1")

            retrieved shouldHaveSize 1
            val trial = retrieved[0]
            trial.testQuery.query shouldBe "What is AI?"
            trial.testQuery.intent shouldBe "explain"
            trial.toolsUsed shouldBe listOf("search", "calculator")
            val tokenUsage = trial.tokenUsage
            tokenUsage.shouldNotBeNull()
            tokenUsage.promptTokens shouldBe 100
            tokenUsage.completionTokens shouldBe 50
            tokenUsage.totalTokens shouldBe 150
            trial.evaluations shouldHaveSize 2
            trial.evaluations[0].tier shouldBe EvaluationTier.STRUCTURAL
            trial.evaluations[0].passed shouldBe true
            trial.evaluations[1].tier shouldBe EvaluationTier.RULES
            trial.evaluations[1].score shouldBe 0.85
        }

        @Test
        fun `should return empty list for nonexistent experiment trials`() {
            store.getTrials("nonexistent").shouldBeEmpty()
        }

        @Test
        fun `should append trials on subsequent saves`() {
            store.saveTrials("exp-1", listOf(createTrial(id = "t-1")))
            store.saveTrials("exp-1", listOf(createTrial(id = "t-2")))

            store.getTrials("exp-1") shouldHaveSize 2
        }

        @Test
        fun `should isolate trials by experiment`() {
            store.saveTrials("exp-1", listOf(createTrial(id = "t-1", experimentId = "exp-1")))
            store.saveTrials("exp-2", listOf(createTrial(id = "t-2", experimentId = "exp-2")))

            store.getTrials("exp-1") shouldHaveSize 1
            store.getTrials("exp-2") shouldHaveSize 1
        }

        @Test
        fun `should handle trial with null optional fields`() {
            val minimalTrial = Trial(
                id = "t-minimal",
                experimentId = "exp-1",
                promptVersionId = "v1",
                promptVersionNumber = 1,
                testQuery = TestQuery(query = "test"),
                response = null,
                success = false,
                errorMessage = null,
                toolsUsed = emptyList(),
                tokenUsage = null,
                evaluations = emptyList()
            )
            store.saveTrials("exp-1", listOf(minimalTrial))

            val retrieved = store.getTrials("exp-1")
            retrieved shouldHaveSize 1
            retrieved[0].response.shouldBeNull()
            retrieved[0].tokenUsage.shouldBeNull()
            retrieved[0].toolsUsed.shouldBeEmpty()
            retrieved[0].evaluations.shouldBeEmpty()
        }

        @Test
        fun `should be idempotent when saving same trial ids again`() {
            val trial = createTrial(id = "t-1")
            store.saveTrials("exp-1", listOf(trial))

            // Same trial ID saved again — should not throw or duplicate
            assertDoesNotThrow { store.saveTrials("exp-1", listOf(trial)) }
            store.getTrials("exp-1") shouldHaveSize 1
        }
    }

    @Nested
    inner class ReportOperations {

        @Test
        fun `should save and retrieve report`() {
            val report = createReport()

            store.saveReport("exp-1", report)
            val retrieved = store.getReport("exp-1")

            retrieved.shouldNotBeNull()
            retrieved.experimentId shouldBe "exp-1"
            retrieved.experimentName shouldBe "Test Experiment"
            retrieved.totalTrials shouldBe 4
        }

        @Test
        fun `should preserve report complex fields through round-trip`() {
            store.saveReport("exp-1", createReport())
            val retrieved = store.getReport("exp-1")

            retrieved.shouldNotBeNull()
            retrieved.versionSummaries shouldHaveSize 1
            val summary = retrieved.versionSummaries[0]
            summary.versionId shouldBe "v1"
            summary.isBaseline shouldBe true
            summary.passRate shouldBe 1.0
            summary.tierBreakdown.size shouldBe 2
            summary.toolUsageFrequency["search"] shouldBe 2

            retrieved.queryComparisons shouldHaveSize 1
            retrieved.recommendation.bestVersionId shouldBe "v1"
            retrieved.recommendation.confidence shouldBe RecommendationConfidence.HIGH
            retrieved.recommendation.improvements shouldHaveSize 1
            retrieved.recommendation.warnings shouldHaveSize 1
        }

        @Test
        fun `should return null for nonexistent experiment report`() {
            store.getReport("nonexistent").shouldBeNull()
        }

        @Test
        fun `should overwrite existing report`() {
            store.saveReport("exp-1", createReport())
            val updated = createReport().copy(totalTrials = 10)
            store.saveReport("exp-1", updated)

            val retrieved = store.getReport("exp-1")
            retrieved.shouldNotBeNull()
            retrieved.totalTrials shouldBe 10
        }
    }
}
