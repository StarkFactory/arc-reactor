package com.arc.reactor.promptlab

import com.arc.reactor.promptlab.model.ExperimentResult
import com.arc.reactor.promptlab.model.LiveExperimentStatus
import com.arc.reactor.promptlab.model.PromptExperiment
import com.arc.reactor.promptlab.model.PromptVariant
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * InMemoryLiveExperimentStore 테스트.
 *
 * 라이브 A/B 테스트 저장소의 CRUD, 상태 전이, 결과 기록, 보고서 생성을 검증한다.
 */
class LiveExperimentStoreTest {

    private lateinit var store: InMemoryLiveExperimentStore

    @BeforeEach
    fun setup() {
        store = InMemoryLiveExperimentStore()
    }

    private fun createExperiment(
        id: String = "live-exp-1",
        name: String = "Test A/B",
        trafficPercent: Int = 20,
        status: LiveExperimentStatus = LiveExperimentStatus.DRAFT
    ) = PromptExperiment(
        id = id,
        name = name,
        controlPrompt = "You are a helpful assistant.",
        variantPrompt = "You are a concise assistant.",
        trafficPercent = trafficPercent,
        status = status
    )

    @Nested
    inner class ExperimentCrud {

        @Test
        fun `save와 get은 실험을 저장하고 조회해야 한다`() {
            val experiment = createExperiment()
            store.save(experiment)
            val retrieved = store.get(experiment.id)
            retrieved.shouldNotBeNull()
            retrieved.name shouldBe "Test A/B"
            retrieved.trafficPercent shouldBe 20
        }

        @Test
        fun `존재하지 않는 ID는 null을 반환해야 한다`() {
            store.get("nonexistent").shouldBeNull()
        }

        @Test
        fun `list는 상태별 필터를 적용해야 한다`() {
            store.save(createExperiment("e1", status = LiveExperimentStatus.DRAFT))
            store.save(createExperiment("e2", status = LiveExperimentStatus.RUNNING))
            store.save(createExperiment("e3", status = LiveExperimentStatus.COMPLETED))

            store.list() shouldHaveSize 3
            store.list(LiveExperimentStatus.DRAFT) shouldHaveSize 1
            store.list(LiveExperimentStatus.RUNNING) shouldHaveSize 1
            store.list(LiveExperimentStatus.COMPLETED) shouldHaveSize 1
        }

        @Test
        fun `delete는 실험과 결과를 모두 삭제해야 한다`() {
            val experiment = createExperiment()
            store.save(experiment)
            store.start(experiment.id)
            store.recordResult(
                ExperimentResult(
                    experimentId = experiment.id,
                    variant = PromptVariant.CONTROL,
                    success = true,
                    latencyMs = 100
                )
            )

            store.delete(experiment.id)

            store.get(experiment.id).shouldBeNull()
            store.getResults(experiment.id).shouldBeEmpty()
        }

        @Test
        fun `delete는 존재하지 않는 실험에 대해 멱등이어야 한다`() {
            // 예외 없이 실행되어야 한다
            store.delete("nonexistent")
        }
    }

    @Nested
    inner class StatusTransitions {

        @Test
        fun `start는 DRAFT 실험을 RUNNING으로 전이해야 한다`() {
            store.save(createExperiment())
            val started = store.start("live-exp-1")
            started.shouldNotBeNull()
            started.status shouldBe LiveExperimentStatus.RUNNING
            started.startedAt.shouldNotBeNull()
        }

        @Test
        fun `start는 DRAFT가 아닌 실험에 대해 null을 반환해야 한다`() {
            store.save(createExperiment(status = LiveExperimentStatus.RUNNING))
            store.start("live-exp-1").shouldBeNull()
        }

        @Test
        fun `start는 존재하지 않는 실험에 대해 null을 반환해야 한다`() {
            store.start("nonexistent").shouldBeNull()
        }

        @Test
        fun `stop은 RUNNING 실험을 COMPLETED로 전이해야 한다`() {
            store.save(createExperiment())
            store.start("live-exp-1")
            val stopped = store.stop("live-exp-1")
            stopped.shouldNotBeNull()
            stopped.status shouldBe LiveExperimentStatus.COMPLETED
            stopped.completedAt.shouldNotBeNull()
        }

        @Test
        fun `stop은 RUNNING이 아닌 실험에 대해 null을 반환해야 한다`() {
            store.save(createExperiment())
            store.stop("live-exp-1").shouldBeNull()
        }

        @Test
        fun `listRunning은 RUNNING 상태의 실험만 반환해야 한다`() {
            store.save(createExperiment("e1", status = LiveExperimentStatus.DRAFT))
            store.save(createExperiment("e2", status = LiveExperimentStatus.RUNNING))
            store.save(createExperiment("e3", status = LiveExperimentStatus.COMPLETED))

            val running = store.listRunning()
            running shouldHaveSize 1
            running[0].id shouldBe "e2"
        }
    }

    @Nested
    inner class ResultRecording {

        @Test
        fun `recordResult는 RUNNING 실험에 결과를 기록해야 한다`() {
            store.save(createExperiment())
            store.start("live-exp-1")

            store.recordResult(
                ExperimentResult(
                    experimentId = "live-exp-1",
                    variant = PromptVariant.CONTROL,
                    success = true,
                    latencyMs = 150
                )
            )

            val results = store.getResults("live-exp-1")
            results shouldHaveSize 1
            results[0].success shouldBe true
        }

        @Test
        fun `recordResult는 비RUNNING 실험에 대해 결과를 무시해야 한다`() {
            store.save(createExperiment())
            // DRAFT 상태이므로 기록하지 않아야 한다

            store.recordResult(
                ExperimentResult(
                    experimentId = "live-exp-1",
                    variant = PromptVariant.CONTROL,
                    success = true,
                    latencyMs = 100
                )
            )

            store.getResults("live-exp-1").shouldBeEmpty()
        }

        @Test
        fun `recordResult는 메트릭을 갱신해야 한다`() {
            store.save(createExperiment())
            store.start("live-exp-1")

            // control 2회: 1 성공, 1 실패
            store.recordResult(result("live-exp-1", PromptVariant.CONTROL, true, 100))
            store.recordResult(result("live-exp-1", PromptVariant.CONTROL, false, 200))

            // variant 3회: 2 성공, 1 실패
            store.recordResult(result("live-exp-1", PromptVariant.VARIANT, true, 50))
            store.recordResult(result("live-exp-1", PromptVariant.VARIANT, true, 150))
            store.recordResult(result("live-exp-1", PromptVariant.VARIANT, false, 100))

            val metrics = store.get("live-exp-1")!!.metrics
            metrics.controlTotalCount shouldBe 2
            metrics.controlSuccessCount shouldBe 1
            metrics.controlSuccessRate shouldBe 0.5
            metrics.controlAvgLatencyMs shouldBe 150

            metrics.variantTotalCount shouldBe 3
            metrics.variantSuccessCount shouldBe 2
            metrics.variantSuccessRate shouldBeGreaterThan 0.6
            metrics.variantSuccessRate shouldBeLessThan 0.7
            metrics.variantAvgLatencyMs shouldBe 100

            metrics.totalSampleCount shouldBe 5
        }

        @Test
        fun `getResults는 존재하지 않는 실험에 대해 빈 리스트를 반환해야 한다`() {
            store.getResults("nonexistent").shouldBeEmpty()
        }
    }

    @Nested
    inner class ReportGeneration {

        @Test
        fun `getReport는 메트릭 기반 보고서를 생성해야 한다`() {
            store.save(createExperiment())
            store.start("live-exp-1")

            // 충분한 샘플 기록 (승자 판정을 위해 30개 이상)
            repeat(20) {
                store.recordResult(result("live-exp-1", PromptVariant.CONTROL, true, 100))
            }
            repeat(10) {
                store.recordResult(result("live-exp-1", PromptVariant.CONTROL, false, 200))
            }
            repeat(25) {
                store.recordResult(result("live-exp-1", PromptVariant.VARIANT, true, 80))
            }
            repeat(5) {
                store.recordResult(result("live-exp-1", PromptVariant.VARIANT, false, 150))
            }

            val report = store.getReport("live-exp-1")
            report.shouldNotBeNull()
            report.experimentId shouldBe "live-exp-1"
            report.experimentName shouldBe "Test A/B"
            report.metrics.totalSampleCount shouldBe 60
        }

        @Test
        fun `getReport는 존재하지 않는 실험에 대해 null을 반환해야 한다`() {
            store.getReport("nonexistent").shouldBeNull()
        }

        @Test
        fun `getReport는 샘플 부족 시 winner를 null로 설정해야 한다`() {
            store.save(createExperiment())
            store.start("live-exp-1")

            // 소수의 결과만 기록
            store.recordResult(result("live-exp-1", PromptVariant.CONTROL, true, 100))
            store.recordResult(result("live-exp-1", PromptVariant.VARIANT, true, 80))

            val report = store.getReport("live-exp-1")
            report.shouldNotBeNull()
            report.winner.shouldBeNull()
            report.confidenceLevel shouldBe "insufficient_data"
        }
    }

    @Nested
    inner class Eviction {

        @Test
        fun `maxEntries 초과 시 완료된 오래된 실험을 퇴출해야 한다`() {
            val smallStore = InMemoryLiveExperimentStore(maxEntries = 3)

            smallStore.save(createExperiment("e1", status = LiveExperimentStatus.COMPLETED))
            smallStore.save(createExperiment("e2", status = LiveExperimentStatus.COMPLETED))
            smallStore.save(createExperiment("e3", status = LiveExperimentStatus.RUNNING))
            // 4번째 추가 시 e1이 퇴출되어야 한다
            smallStore.save(createExperiment("e4", status = LiveExperimentStatus.DRAFT))

            smallStore.get("e1").shouldBeNull()
            smallStore.get("e2").shouldNotBeNull()
            smallStore.get("e3").shouldNotBeNull()
            smallStore.get("e4").shouldNotBeNull()
        }

        @Test
        fun `maxResultsPerExperiment 초과 시 오래된 결과를 제거해야 한다`() {
            val smallStore = InMemoryLiveExperimentStore(maxResultsPerExperiment = 5)
            smallStore.save(createExperiment())
            smallStore.start("live-exp-1")

            repeat(10) {
                smallStore.recordResult(
                    result("live-exp-1", PromptVariant.CONTROL, true, 100)
                )
            }

            val results = smallStore.getResults("live-exp-1")
            results.size shouldBe 5
        }
    }

    private fun result(
        experimentId: String,
        variant: PromptVariant,
        success: Boolean,
        latencyMs: Long
    ) = ExperimentResult(
        experimentId = experimentId,
        variant = variant,
        success = success,
        latencyMs = latencyMs
    )
}
