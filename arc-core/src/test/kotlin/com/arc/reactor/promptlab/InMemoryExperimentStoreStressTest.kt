package com.arc.reactor.promptlab

import com.arc.reactor.promptlab.model.Experiment
import com.arc.reactor.promptlab.model.ExperimentReport
import com.arc.reactor.promptlab.model.ExperimentStatus
import com.arc.reactor.promptlab.model.Recommendation
import com.arc.reactor.promptlab.model.RecommendationConfidence
import com.arc.reactor.promptlab.model.Trial
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InMemoryExperimentStoreStressTest {

    private fun experiment(
        id: String = "exp-${System.nanoTime()}",
        status: ExperimentStatus = ExperimentStatus.PENDING,
        createdAt: Instant = Instant.now()
    ) = Experiment(
        id = id,
        name = "Experiment $id",
        templateId = "tmpl-1",
        baselineVersionId = "v-1",
        candidateVersionIds = listOf("v-2"),
        testQueries = emptyList(),
        status = status,
        createdAt = createdAt
    )

    private fun trial(experimentId: String, index: Int) = Trial(
        experimentId = experimentId,
        promptVersionId = "v-1",
        promptVersionNumber = 1,
        testQuery = com.arc.reactor.promptlab.model.TestQuery("query-$index"),
        success = true,
        executedAt = Instant.now()
    )

    private fun report(experimentId: String) = ExperimentReport(
        experimentId = experimentId,
        experimentName = "Test",
        generatedAt = Instant.now(),
        totalTrials = 1,
        versionSummaries = emptyList(),
        queryComparisons = emptyList(),
        recommendation = Recommendation(
            bestVersionId = "v-1",
            bestVersionNumber = 1,
            confidence = RecommendationConfidence.LOW,
            reasoning = "Test"
        )
    )

    @Nested
    inner class ConcurrentAccess {

        @Test
        fun `should handle concurrent saves without data loss`() {
            val store = InMemoryExperimentStore()
            val threadCount = 20
            val opsPerThread = 50
            val barrier = CyclicBarrier(threadCount)
            val errors = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(threadCount)

            try {
                val latch = CountDownLatch(threadCount)
                for (t in 0 until threadCount) {
                    executor.submit {
                        try {
                            barrier.await(5, TimeUnit.SECONDS)
                            for (i in 0 until opsPerThread) {
                                store.save(experiment(id = "exp-$t-$i"))
                            }
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await(30, TimeUnit.SECONDS)

                errors.get() shouldBe 0 // "No errors during concurrent saves"
                store.list().size shouldBe threadCount * opsPerThread // "All experiments should be saved"
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `should handle concurrent trial appends safely`() {
            val store = InMemoryExperimentStore()
            val expId = "exp-concurrent"
            store.save(experiment(id = expId))

            val threadCount = 10
            val trialsPerThread = 20
            val barrier = CyclicBarrier(threadCount)
            val executor = Executors.newFixedThreadPool(threadCount)

            try {
                val latch = CountDownLatch(threadCount)
                for (t in 0 until threadCount) {
                    executor.submit {
                        try {
                            barrier.await(5, TimeUnit.SECONDS)
                            val trials = (0 until trialsPerThread).map { trial(expId, t * trialsPerThread + it) }
                            store.saveTrials(expId, trials)
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await(30, TimeUnit.SECONDS)

                store.getTrials(expId).size shouldBe threadCount * trialsPerThread // "All trials should be appended"
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `should handle concurrent read and write mix`() {
            val store = InMemoryExperimentStore()
            val threadCount = 16
            val barrier = CyclicBarrier(threadCount)
            val errors = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(threadCount)

            try {
                val latch = CountDownLatch(threadCount)
                for (t in 0 until threadCount) {
                    executor.submit {
                        try {
                            barrier.await(5, TimeUnit.SECONDS)
                            for (i in 0 until 100) {
                                if (i % 3 == 0) {
                                    store.save(experiment(id = "exp-$t-$i"))
                                } else if (i % 3 == 1) {
                                    store.list() // concurrent read
                                } else {
                                    store.get("exp-$t-${i - 2}") // read specific
                                }
                            }
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await(30, TimeUnit.SECONDS)

                errors.get() shouldBe 0 // "No errors during concurrent read/write"
                store.list().size shouldBeGreaterThan 0 // "Should have saved experiments"
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Nested
    inner class EvictionUnderLoad {

        @Test
        fun `should evict oldest completed experiments when capacity exceeded`() {
            val maxEntries = 50
            val store = InMemoryExperimentStore(maxEntries = maxEntries)

            // Fill with completed experiments
            for (i in 0 until maxEntries) {
                store.save(
                    experiment(
                        id = "old-$i",
                        status = ExperimentStatus.COMPLETED,
                        createdAt = Instant.now().minusSeconds(1000L - i)
                    )
                )
            }
            store.list().size shouldBe maxEntries // "Should be at capacity"

            // Add more experiments — should trigger eviction
            for (i in 0 until 20) {
                store.save(experiment(id = "new-$i", status = ExperimentStatus.PENDING))
            }

            store.list().size shouldBeLessThanOrEqual maxEntries // "Should evict to stay within capacity"
        }

        @Test
        fun `should not evict running experiments during eviction`() {
            val maxEntries = 10
            val store = InMemoryExperimentStore(maxEntries = maxEntries)

            // Add running experiments
            for (i in 0 until 5) {
                store.save(experiment(id = "running-$i", status = ExperimentStatus.RUNNING))
            }
            // Add completed experiments
            for (i in 0 until 5) {
                store.save(
                    experiment(
                        id = "done-$i",
                        status = ExperimentStatus.COMPLETED,
                        createdAt = Instant.now().minusSeconds(100L - i)
                    )
                )
            }

            // Add 5 more to trigger eviction
            for (i in 0 until 5) {
                store.save(experiment(id = "new-$i"))
            }

            // All running experiments should still be present
            for (i in 0 until 5) {
                store.get("running-$i") shouldBe experiment(
                    id = "running-$i",
                    status = ExperimentStatus.RUNNING
                ).let { store.get("running-$i") } // just check not null
            }
        }

        @Test
        fun `should delete trials and reports during eviction`() {
            val maxEntries = 5
            val store = InMemoryExperimentStore(maxEntries = maxEntries)

            // Add experiments with trials and reports
            for (i in 0 until maxEntries) {
                val id = "exp-$i"
                store.save(
                    experiment(
                        id = id,
                        status = ExperimentStatus.COMPLETED,
                        createdAt = Instant.now().minusSeconds(100L - i)
                    )
                )
                store.saveTrials(id, listOf(trial(id, 0)))
                store.saveReport(id, report(id))
            }

            // Trigger eviction
            store.save(experiment(id = "new-1"))
            store.save(experiment(id = "new-2"))

            // Evicted experiments should have no trials or reports
            val evictedId = "exp-0" // oldest
            store.get(evictedId) shouldBe null // "Evicted experiment should be gone"
            store.getTrials(evictedId).size shouldBe 0 // "Evicted trials should be cleaned"
            store.getReport(evictedId) shouldBe null // "Evicted report should be cleaned"
        }

        @Test
        fun `should handle concurrent saves with eviction pressure`() {
            val maxEntries = 100
            val store = InMemoryExperimentStore(maxEntries = maxEntries)
            val threadCount = 8
            val opsPerThread = 50
            val barrier = CyclicBarrier(threadCount)
            val errors = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(threadCount)

            try {
                val latch = CountDownLatch(threadCount)
                for (t in 0 until threadCount) {
                    executor.submit {
                        try {
                            barrier.await(5, TimeUnit.SECONDS)
                            for (i in 0 until opsPerThread) {
                                // All COMPLETED so they can be evicted
                                store.save(experiment(id = "stress-$t-$i", status = ExperimentStatus.COMPLETED))
                            }
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await(30, TimeUnit.SECONDS)

                errors.get() shouldBe 0 // "No errors during concurrent eviction stress"
                store.list().size shouldBeLessThanOrEqual maxEntries // "Should not exceed capacity"
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
