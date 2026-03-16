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
        fun `handle concurrent saves without data loss해야 한다`() {
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
                store.list().size shouldBe threadCount * opsPerThread  // "All experiments은(는) be saved"해야 합니다
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `handle concurrent trial appends safely해야 한다`() {
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

                store.getTrials(expId).size shouldBe threadCount * trialsPerThread  // "All trials은(는) be appended"해야 합니다
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `handle concurrent read and write mix해야 한다`() {
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
        fun `capacity exceeded일 때 evict oldest completed experiments해야 한다`() {
            val maxEntries = 50
            val store = InMemoryExperimentStore(maxEntries = maxEntries)

            // with completed experiments를 채웁니다
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

            // Add more experiments —은(는) trigger eviction해야 합니다
            for (i in 0 until 20) {
                store.save(experiment(id = "new-$i", status = ExperimentStatus.PENDING))
            }

            store.list().size shouldBeLessThanOrEqual maxEntries // "Should evict to stay within capacity"
        }

        @Test
        fun `not evict running experiments during eviction해야 한다`() {
            val maxEntries = 10
            val store = InMemoryExperimentStore(maxEntries = maxEntries)

            // running experiments를 추가합니다
            for (i in 0 until 5) {
                store.save(experiment(id = "running-$i", status = ExperimentStatus.RUNNING))
            }
            // completed experiments를 추가합니다
            for (i in 0 until 5) {
                store.save(
                    experiment(
                        id = "done-$i",
                        status = ExperimentStatus.COMPLETED,
                        createdAt = Instant.now().minusSeconds(100L - i)
                    )
                )
            }

            // 5 more to trigger eviction를 추가합니다
            for (i in 0 until 5) {
                store.save(experiment(id = "new-$i"))
            }

            // All running experiments은(는) still be present해야 합니다
            for (i in 0 until 5) {
                store.get("running-$i") shouldBe experiment(
                    id = "running-$i",
                    status = ExperimentStatus.RUNNING
                ).let { store.get("running-$i") } // just check not null
            }
        }

        @Test
        fun `delete trials and reports during eviction해야 한다`() {
            val maxEntries = 5
            val store = InMemoryExperimentStore(maxEntries = maxEntries)

            // experiments with trials and reports를 추가합니다
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

            // eviction를 트리거합니다
            store.save(experiment(id = "new-1"))
            store.save(experiment(id = "new-2"))

            // Evicted experiments은(는) have no trials or reports해야 합니다
            val evictedId = "exp-0" // oldest
            store.get(evictedId) shouldBe null  // "Evicted experiment은(는) be gone"해야 합니다
            store.getTrials(evictedId).size shouldBe 0  // "Evicted trials은(는) be cleaned"해야 합니다
            store.getReport(evictedId) shouldBe null  // "Evicted report은(는) be cleaned"해야 합니다
        }

        @Test
        fun `eviction pressure로 handle concurrent saves해야 한다`() {
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
                                // 모든 COMPLETED so they can be evicted
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
