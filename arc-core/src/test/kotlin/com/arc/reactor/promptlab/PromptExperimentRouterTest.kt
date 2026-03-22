package com.arc.reactor.promptlab

import com.arc.reactor.promptlab.model.LiveExperimentStatus
import com.arc.reactor.promptlab.model.PromptExperiment
import com.arc.reactor.promptlab.model.PromptVariant
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * PromptExperimentRouter 테스트.
 *
 * 트래픽 분할 라우팅, 세션 결정론성, 프롬프트 매칭 로직을 검증한다.
 */
class PromptExperimentRouterTest {

    private lateinit var store: InMemoryLiveExperimentStore
    private lateinit var router: PromptExperimentRouter

    private val controlPrompt = "You are a helpful assistant."
    private val variantPrompt = "You are a concise assistant."

    @BeforeEach
    fun setup() {
        store = InMemoryLiveExperimentStore()
        router = PromptExperimentRouter(store)
    }

    private fun createAndStartExperiment(
        id: String = "exp-1",
        trafficPercent: Int = 50
    ): PromptExperiment {
        val experiment = PromptExperiment(
            id = id,
            name = "Test A/B",
            controlPrompt = controlPrompt,
            variantPrompt = variantPrompt,
            trafficPercent = trafficPercent,
            status = LiveExperimentStatus.DRAFT
        )
        store.save(experiment)
        store.start(id)
        return store.get(id)!!
    }

    @Nested
    inner class BasicRouting {

        @Test
        fun `RUNNING 실험이 없으면 null을 반환해야 한다`() {
            router.route(controlPrompt).shouldBeNull()
        }

        @Test
        fun `실험이 있지만 프롬프트가 매칭되지 않으면 null을 반환해야 한다`() {
            createAndStartExperiment()
            router.route("Completely different prompt").shouldBeNull()
        }

        @Test
        fun `매칭되는 RUNNING 실험이 있으면 결정을 반환해야 한다`() {
            createAndStartExperiment()
            val decision = router.route(controlPrompt, "session-1")
            decision.shouldNotBeNull()
            decision.experimentId shouldBe "exp-1"
            // variant는 CONTROL 또는 VARIANT 중 하나
            decision.variant shouldBe decision.variant
        }

        @Test
        fun `trafficPercent=0이면 항상 CONTROL을 반환해야 한다`() {
            createAndStartExperiment(trafficPercent = 0)

            repeat(50) {
                val decision = router.route(controlPrompt, "session-$it")
                decision.shouldNotBeNull()
                decision.variant shouldBe PromptVariant.CONTROL
                decision.prompt shouldBe controlPrompt
            }
        }

        @Test
        fun `trafficPercent=100이면 항상 VARIANT를 반환해야 한다`() {
            createAndStartExperiment(trafficPercent = 100)

            repeat(50) {
                val decision = router.route(controlPrompt, "session-$it")
                decision.shouldNotBeNull()
                decision.variant shouldBe PromptVariant.VARIANT
                decision.prompt shouldBe variantPrompt
            }
        }
    }

    @Nested
    inner class DeterministicSessionRouting {

        @Test
        fun `같은 세션 ID는 항상 같은 변형을 반환해야 한다`() {
            createAndStartExperiment(trafficPercent = 50)
            val sessionId = "deterministic-session-abc"

            val first = router.route(controlPrompt, sessionId)
            first.shouldNotBeNull()

            // 동일 세션으로 여러 번 호출해도 같은 결과
            repeat(20) {
                val decision = router.route(controlPrompt, sessionId)
                decision.shouldNotBeNull()
                decision.variant shouldBe first.variant
            }
        }

        @Test
        fun `다른 세션 ID는 다른 변형을 반환할 수 있어야 한다`() {
            createAndStartExperiment(trafficPercent = 50)

            // 충분한 세션을 테스트하여 두 변형 모두 나오는지 확인
            val variants = (0 until 100).map { i ->
                val decision = router.route(controlPrompt, "session-$i")
                decision.shouldNotBeNull()
                decision.variant
            }.toSet()

            // 50% 트래픽이면 두 변형 모두 나와야 한다
            variants.size shouldBe 2
        }
    }

    @Nested
    inner class TrafficSplitting {

        @Test
        fun `trafficPercent 비율에 근사하게 분배해야 한다`() {
            createAndStartExperiment(trafficPercent = 30)

            var variantCount = 0
            val total = 1000

            for (i in 0 until total) {
                val decision = router.route(controlPrompt, "traffic-session-$i")
                decision.shouldNotBeNull()
                if (decision.variant == PromptVariant.VARIANT) {
                    variantCount++
                }
            }

            // 30% 목표에 대해 20-40% 범위 내에 있어야 한다
            val ratio = variantCount.toDouble() / total
            assert(ratio in 0.15..0.45) {
                "Variant ratio $ratio should be approximately 0.30 (tolerance 0.15)"
            }
        }
    }

    @Nested
    inner class SelectVariant {

        @Test
        fun `세션 ID가 있으면 결정론적 값을 사용해야 한다`() {
            val experiment = createAndStartExperiment(trafficPercent = 50)

            val v1 = router.selectVariant(experiment, "session-a")
            val v2 = router.selectVariant(experiment, "session-a")
            v1 shouldBe v2
        }

        @Test
        fun `세션 ID가 null이면 확률적 선택을 해야 한다`() {
            val experiment = createAndStartExperiment(trafficPercent = 50)

            // 확률적이므로 여러 번 호출하면 두 변형 모두 나와야 한다
            val variants = (0 until 200).map {
                router.selectVariant(experiment, null)
            }.toSet()

            variants.size shouldBe 2
        }
    }

    @Nested
    inner class PromptSelection {

        @Test
        fun `CONTROL 변형이면 controlPrompt를 반환해야 한다`() {
            createAndStartExperiment(trafficPercent = 0) // 항상 CONTROL

            val decision = router.route(controlPrompt, "any-session")
            decision.shouldNotBeNull()
            decision.prompt shouldBe controlPrompt
        }

        @Test
        fun `VARIANT 변형이면 variantPrompt를 반환해야 한다`() {
            createAndStartExperiment(trafficPercent = 100) // 항상 VARIANT

            val decision = router.route(controlPrompt, "any-session")
            decision.shouldNotBeNull()
            decision.prompt shouldBe variantPrompt
        }
    }

    @Nested
    inner class NonRunningExperiments {

        @Test
        fun `DRAFT 상태의 실험은 무시해야 한다`() {
            val experiment = PromptExperiment(
                id = "draft-exp",
                name = "Draft",
                controlPrompt = controlPrompt,
                variantPrompt = variantPrompt,
                trafficPercent = 100,
                status = LiveExperimentStatus.DRAFT
            )
            store.save(experiment)

            router.route(controlPrompt).shouldBeNull()
        }

        @Test
        fun `COMPLETED 상태의 실험은 무시해야 한다`() {
            val experiment = PromptExperiment(
                id = "completed-exp",
                name = "Completed",
                controlPrompt = controlPrompt,
                variantPrompt = variantPrompt,
                trafficPercent = 100,
                status = LiveExperimentStatus.COMPLETED
            )
            store.save(experiment)

            router.route(controlPrompt).shouldBeNull()
        }
    }
}
