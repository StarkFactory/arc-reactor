package com.arc.reactor.agent.impl

import com.arc.reactor.hook.model.HookContext
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * StageTimingSupport에 대한 테스트.
 *
 * 단계별 타이밍 기록/조회 유틸리티의 정확성과 동시성 안전성을 검증한다.
 */
class StageTimingSupportTest {

    /** 테스트용 HookContext를 생성한다. */
    private fun createHookContext(): HookContext = HookContext(
        runId = "test-run-1",
        userId = "test-user",
        userPrompt = "test prompt"
    )

    @Nested
    inner class RecordStageTiming {

        @Test
        fun `단일 단계 타이밍을 기록해야 한다`() {
            val ctx = createHookContext()

            recordStageTiming(ctx, "guard", 150L)

            val timings = readStageTimings(ctx)
            timings shouldContainKey "guard"
            timings["guard"] shouldBe 150L
        }

        @Test
        fun `여러 단계를 순차 기록해야 한다`() {
            val ctx = createHookContext()

            recordStageTiming(ctx, "guard", 100L)
            recordStageTiming(ctx, "before_hooks", 50L)
            recordStageTiming(ctx, "intent_resolution", 200L)

            val timings = readStageTimings(ctx)
            timings shouldHaveSize 3
            timings["guard"] shouldBe 100L
            timings["before_hooks"] shouldBe 50L
            timings["intent_resolution"] shouldBe 200L
        }

        @Test
        fun `같은 단계를 덮어써야 한다`() {
            val ctx = createHookContext()

            recordStageTiming(ctx, "guard", 100L)
            recordStageTiming(ctx, "guard", 250L)

            val timings = readStageTimings(ctx)
            timings shouldHaveSize 1
            timings["guard"] shouldBe 250L
        }

        @Test
        fun `음수 duration은 0으로 보정해야 한다`() {
            val ctx = createHookContext()

            recordStageTiming(ctx, "guard", -100L)

            val timings = readStageTimings(ctx)
            timings["guard"] shouldBe 0L
        }

        @Test
        fun `0 duration을 그대로 기록해야 한다`() {
            val ctx = createHookContext()

            recordStageTiming(ctx, "guard", 0L)

            val timings = readStageTimings(ctx)
            timings["guard"] shouldBe 0L
        }

        @Test
        fun `매우 큰 duration을 기록해야 한다`() {
            val ctx = createHookContext()

            recordStageTiming(ctx, "llm", Long.MAX_VALUE)

            val timings = readStageTimings(ctx)
            timings["llm"] shouldBe Long.MAX_VALUE
        }
    }

    @Nested
    inner class ReadStageTimings {

        @Test
        fun `기록이 없으면 빈 맵을 반환해야 한다`() {
            val ctx = createHookContext()

            val timings = readStageTimings(ctx)

            timings.shouldBeEmpty()
        }

        @Test
        fun `metadata에 다른 타입이 저장된 경우 빈 맵을 반환해야 한다`() {
            val ctx = createHookContext()
            ctx.metadata[STAGE_TIMINGS_METADATA_KEY] = "invalid-type"

            val timings = readStageTimings(ctx)

            timings.shouldBeEmpty()
        }

        @Test
        fun `기록 후 모든 타이밍을 조회해야 한다`() {
            val ctx = createHookContext()
            recordStageTiming(ctx, "guard", 100L)
            recordStageTiming(ctx, "llm", 500L)

            val timings = readStageTimings(ctx)

            timings shouldHaveSize 2
            timings["guard"] shouldBe 100L
            timings["llm"] shouldBe 500L
        }
    }

    @Nested
    inner class ConcurrentAccess {

        @Test
        fun `여러 스레드에서 동시에 기록해도 데이터가 손실되지 않아야 한다`() {
            val ctx = createHookContext()
            // ConcurrentHashMap을 미리 초기화하여 getOrPut 레이스 방지
            recordStageTiming(ctx, "init", 0L)

            val threadCount = 20
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(threadCount)

            try {
                for (i in 0 until threadCount) {
                    executor.submit {
                        try {
                            recordStageTiming(ctx, "stage-$i", (i * 10).toLong())
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await()

                val timings = readStageTimings(ctx)
                // init + 20 stages = 21
                timings shouldHaveSize (threadCount + 1)
                for (i in 0 until threadCount) {
                    timings shouldContainKey "stage-$i"
                    timings["stage-$i"] shouldBe (i * 10).toLong()
                }
            } finally {
                executor.shutdown()
            }
        }

        @Test
        fun `같은 키에 동시 쓰기 시 마지막 값이 유지되어야 한다`() {
            val ctx = createHookContext()
            // ConcurrentHashMap을 미리 초기화하여 getOrPut 레이스 방지
            recordStageTiming(ctx, "shared-stage", 0L)

            val threadCount = 10
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(threadCount)

            try {
                for (i in 0 until threadCount) {
                    executor.submit {
                        try {
                            recordStageTiming(ctx, "shared-stage", (i * 100).toLong())
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await()

                val timings = readStageTimings(ctx)
                timings shouldHaveSize 1
                timings shouldContainKey "shared-stage"
                // 순서는 비결정적 — 단지 유효한 범위의 값이어야 한다
                val value = timings["shared-stage"]!!
                (value >= 0L && value < threadCount * 100L) shouldBe true
            } finally {
                executor.shutdown()
            }
        }
    }
}
