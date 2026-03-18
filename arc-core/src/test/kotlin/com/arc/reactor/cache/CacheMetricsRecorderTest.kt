package com.arc.reactor.cache

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CacheMetricsRecorder")
class CacheMetricsRecorderTest {

    @Nested
    @DisplayName("NoOpCacheMetricsRecorder")
    inner class NoOpTest {

        @Test
        @DisplayName("모든 메서드가 예외 없이 실행된다")
        fun allMethodsNoOp() {
            val recorder = NoOpCacheMetricsRecorder()
            recorder.recordExactHit()
            recorder.recordSemanticHit(0.95)
            recorder.recordMiss()
            recorder.recordEstimatedCostSaved("gpt-4o", 1000, 500)
        }
    }

    @Nested
    @DisplayName("MicrometerCacheMetricsRecorder")
    inner class MicrometerTest {

        private lateinit var registry: SimpleMeterRegistry
        private lateinit var recorder: MicrometerCacheMetricsRecorder

        @BeforeEach
        fun setUp() {
            registry = SimpleMeterRegistry()
            recorder = MicrometerCacheMetricsRecorder(registry)
        }

        @Test
        @DisplayName("정확 일치 히트가 type=exact 태그로 기록된다")
        fun recordExactHit() {
            recorder.recordExactHit()
            recorder.recordExactHit()

            val counter = registry.find("arc.cache.hits").tag("type", "exact").counter()
            counter?.count() shouldBe 2.0
        }

        @Test
        @DisplayName("시맨틱 히트가 type=semantic 태그로 기록된다")
        fun recordSemanticHit() {
            recorder.recordSemanticHit(0.92)

            val counter = registry.find("arc.cache.hits").tag("type", "semantic").counter()
            counter?.count() shouldBe 1.0
        }

        @Test
        @DisplayName("시맨틱 유사도 점수가 summary에 기록된다")
        fun recordSemanticSimilarity() {
            recorder.recordSemanticHit(0.85)
            recorder.recordSemanticHit(0.95)

            val summary = registry.find("arc.cache.semantic.similarity").summary()
            summary?.count() shouldBe 2L
            summary!!.mean() shouldBeGreaterThan 0.0
        }

        @Test
        @DisplayName("캐시 미스가 기록된다")
        fun recordMiss() {
            recorder.recordMiss()
            recorder.recordMiss()
            recorder.recordMiss()

            val counter = registry.find("arc.cache.misses").counter()
            counter?.count() shouldBe 3.0
        }

        @Test
        @DisplayName("비용 절감 추정이 모델 태그와 함께 기록된다")
        fun recordCostSaved() {
            recorder.recordEstimatedCostSaved("gpt-4o", 1000, 500)

            val counter = registry.find("arc.cache.cost.saved.estimate")
                .tag("model", "gpt-4o")
                .counter()
            counter!!.count() shouldBeGreaterThan 0.0
        }

        @Test
        @DisplayName("알 수 없는 모델도 기본 단가로 비용이 추정된다")
        fun recordCostSavedUnknownModel() {
            recorder.recordEstimatedCostSaved("my-custom-model", 1000, 500)

            val counter = registry.find("arc.cache.cost.saved.estimate")
                .tag("model", "my-custom-model")
                .counter()
            counter!!.count() shouldBeGreaterThan 0.0
        }

        @Test
        @DisplayName("정확 일치와 시맨틱 히트가 독립적으로 집계된다")
        fun exactAndSemanticCountedIndependently() {
            recorder.recordExactHit()
            recorder.recordExactHit()
            recorder.recordSemanticHit(0.9)

            val exact = registry.find("arc.cache.hits").tag("type", "exact").counter()
            val semantic = registry.find("arc.cache.hits").tag("type", "semantic").counter()

            exact?.count() shouldBe 2.0
            semantic?.count() shouldBe 1.0
        }

        @Test
        @DisplayName("토큰 0이면 비용이 0으로 기록되지 않는다")
        fun zeroCostNotRecorded() {
            recorder.recordEstimatedCostSaved("gpt-4o", 0, 0)

            val counter = registry.find("arc.cache.cost.saved.estimate")
                .tag("model", "gpt-4o")
                .counter()
            // 0 비용이면 increment 호출 안 함 → 카운터가 null이거나 0
            val count = counter?.count() ?: 0.0
            count shouldBe 0.0
        }
    }

    @Nested
    @DisplayName("estimateCostUsd")
    inner class CostEstimationTest {

        @Test
        @DisplayName("GPT-4o 모델의 비용이 합리적 범위 내에 있다")
        fun gpt4oCost() {
            val cost = MicrometerCacheMetricsRecorder.estimateCostUsd("gpt-4o", 1000, 500)
            cost shouldBeGreaterThan 0.0
            cost shouldBeLessThan 1.0
        }

        @Test
        @DisplayName("모델 이름이 대소문자 무관하게 매칭된다")
        fun caseInsensitiveModelMatch() {
            val cost1 = MicrometerCacheMetricsRecorder.estimateCostUsd("GPT-4O", 1000, 500)
            val cost2 = MicrometerCacheMetricsRecorder.estimateCostUsd("gpt-4o", 1000, 500)
            cost1 shouldBe cost2
        }

        @Test
        @DisplayName("접두사가 포함된 모델명도 매칭된다")
        fun prefixedModelMatch() {
            val cost = MicrometerCacheMetricsRecorder.estimateCostUsd("openai/gpt-4o-2024", 1000, 500)
            cost shouldBeGreaterThan 0.0
        }

        @Test
        @DisplayName("gpt-4o-mini가 gpt-4o보다 저렴하게 계산된다")
        fun gpt4oMiniCheaperThanGpt4o() {
            val miniCost = MicrometerCacheMetricsRecorder.estimateCostUsd("gpt-4o-mini", 1000, 500)
            val fullCost = MicrometerCacheMetricsRecorder.estimateCostUsd("gpt-4o", 1000, 500)
            miniCost shouldBeLessThan fullCost
        }

        @Test
        @DisplayName("알 수 없는 모델은 기본 단가를 사용한다")
        fun unknownModelUsesDefault() {
            val cost = MicrometerCacheMetricsRecorder.estimateCostUsd("unknown-model", 1000, 500)
            cost shouldBeGreaterThan 0.0
        }
    }
}
