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
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class InMemoryExperimentStoreTest {

    private lateinit var store: InMemoryExperimentStore

    @BeforeEach
    fun setup() {
        store = InMemoryExperimentStore()
    }

    private fun createExperiment(
        id: String = "exp-1",
        name: String = "Test Experiment",
        templateId: String = "tpl-1",
        status: ExperimentStatus = ExperimentStatus.PENDING,
        createdAt: Instant = Instant.now()
    ) = Experiment(
        id = id,
        name = name,
        templateId = templateId,
        baselineVersionId = "v1",
        candidateVersionIds = listOf("v2"),
        testQueries = listOf(TestQuery(query = "What is AI?")),
        status = status,
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
        testQuery = TestQuery(query = "What is AI?"),
        response = "AI is artificial intelligence.",
        success = true,
        durationMs = 100
    )

    private fun createReport(
        experimentId: String = "exp-1"
    ) = ExperimentReport(
        experimentId = experimentId,
        experimentName = "Test Experiment",
        totalTrials = 2,
        versionSummaries = listOf(
            VersionSummary(
                versionId = "v1",
                versionNumber = 1,
                isBaseline = true,
                totalTrials = 1,
                passCount = 1,
                passRate = 1.0,
                avgScore = 0.9,
                avgDurationMs = 100.0,
                totalTokens = 500,
                tierBreakdown = emptyMap(),
                toolUsageFrequency = emptyMap(),
                errorRate = 0.0
            )
        ),
        queryComparisons = emptyList(),
        recommendation = Recommendation(
            bestVersionId = "v1",
            bestVersionNumber = 1,
            confidence = RecommendationConfidence.HIGH,
            reasoning = "Baseline performs best"
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
        fun `should overwrite existing experiment with same id`() {
            store.save(createExperiment(name = "First"))
            store.save(createExperiment(name = "Second"))

            store.list() shouldHaveSize 1
            val retrieved = store.get("exp-1")
            retrieved.shouldNotBeNull()
            retrieved.name shouldBe "Second"
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
            result.all {
                it.status == ExperimentStatus.COMPLETED
            } shouldBe true
        }

        @Test
        fun `should filter by templateId`() {
            val result = store.list(templateId = "tpl-1")

            result shouldHaveSize 2
            result.all {
                it.templateId == "tpl-1"
            } shouldBe true
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
            val result = store.list()

            result shouldHaveSize 3
        }

        @Test
        fun `should return empty list when no entries match`() {
            val result = store.list(status = ExperimentStatus.FAILED)

            result.shouldBeEmpty()
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
        fun `should return empty list for nonexistent experiment trials`() {
            store.getTrials("nonexistent").shouldBeEmpty()
        }

        @Test
        fun `should append trials on subsequent saves`() {
            store.saveTrials("exp-1", listOf(createTrial(id = "t-1")))
            store.saveTrials("exp-1", listOf(createTrial(id = "t-2")))

            val retrieved = store.getTrials("exp-1")
            retrieved shouldHaveSize 2
        }

        @Test
        fun `should isolate trials by experiment`() {
            store.saveTrials(
                "exp-1",
                listOf(createTrial(id = "t-1", experimentId = "exp-1"))
            )
            store.saveTrials(
                "exp-2",
                listOf(createTrial(id = "t-2", experimentId = "exp-2"))
            )

            store.getTrials("exp-1") shouldHaveSize 1
            store.getTrials("exp-2") shouldHaveSize 1
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
            retrieved.totalTrials shouldBe 2
            retrieved.recommendation.bestVersionId shouldBe "v1"
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

    @Nested
    inner class ConcurrentAccess {

        @Test
        fun `concurrent saves should not lose experiments`() {
            val threadCount = 50
            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = AtomicInteger(0)

            try {
                val futures = (1..threadCount).map { i ->
                    executor.submit {
                        latch.await()
                        try {
                            store.save(createExperiment(id = "exp-$i"))
                        } catch (_: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                }

                latch.countDown()
                for (future in futures) {
                    future.get()
                }

                errors.get() shouldBe 0
                store.list() shouldHaveSize threadCount
            } finally {
                executor.shutdown()
            }
        }

        @Test
        fun `concurrent list should not fail during modifications`() {
            val threadCount = 20
            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = AtomicInteger(0)

            for (i in 1..10) {
                store.save(createExperiment(id = "seed-$i"))
            }

            try {
                val futures = (1..threadCount).map { i ->
                    executor.submit {
                        latch.await()
                        try {
                            if (i % 2 == 0) {
                                store.save(
                                    createExperiment(id = "concurrent-$i")
                                )
                            } else {
                                store.list()
                            }
                        } catch (_: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                }

                latch.countDown()
                for (future in futures) {
                    future.get()
                }

                errors.get() shouldBe 0
            } finally {
                executor.shutdown()
            }
        }
    }
}
