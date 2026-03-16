package com.arc.reactor.promptlab

import com.arc.reactor.prompt.PromptTemplate
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.promptlab.model.Experiment
import com.arc.reactor.promptlab.model.ExperimentStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PromptLab 스케줄러에 대한 테스트.
 *
 * 프롬프트 실험 스케줄링 동작을 검증합니다.
 */
class PromptLabSchedulerTest {

    private val orchestrator: ExperimentOrchestrator = mockk()
    private val promptTemplateStore: PromptTemplateStore = mockk()
    private val properties = PromptLabProperties(
        schedule = ScheduleProperties(
            enabled = true,
            templateIds = listOf("tmpl-1", "tmpl-2")
        )
    )

    private lateinit var scheduler: PromptLabScheduler

    @BeforeEach
    fun setUp() {
        scheduler = PromptLabScheduler(orchestrator, promptTemplateStore, properties)
    }

    private fun completedExperiment(templateId: String) = Experiment(
        name = "Auto-optimize: $templateId",
        templateId = templateId,
        baselineVersionId = "v-1",
        candidateVersionIds = listOf("v-2"),
        testQueries = emptyList(),
        status = ExperimentStatus.COMPLETED,
        createdAt = Instant.now()
    )

    @Nested
    inner class BasicExecution {

        @Test
        fun `each configured template에 대해 run pipeline해야 한다`() {
            coEvery { orchestrator.runAutoPipeline("tmpl-1", null) } returns completedExperiment("tmpl-1")
            coEvery { orchestrator.runAutoPipeline("tmpl-2", null) } returns completedExperiment("tmpl-2")

            scheduler.runScheduled()

            coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-1", null) }
            coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-2", null) }
        }

        @Test
        fun `handle null result from pipeline gracefully해야 한다`() {
            coEvery { orchestrator.runAutoPipeline("tmpl-1", null) } returns null
            coEvery { orchestrator.runAutoPipeline("tmpl-2", null) } returns null

            scheduler.runScheduled()

            scheduler.isRunning() shouldBe false // "Should not be running after completion"
        }

        @Test
        fun `completion 후 update lastRunTime해야 한다`() {
            coEvery { orchestrator.runAutoPipeline(any(), any()) } returns null
            scheduler.lastRunTime() shouldBe null // "Should be null before first run"

            scheduler.runScheduled()

            scheduler.lastRunTime() shouldNotBe null // "Should be set after run"
        }

        @Test
        fun `pass since parameter on subsequent runs해야 한다`() {
            coEvery { orchestrator.runAutoPipeline(any(), any()) } returns null

            scheduler.runScheduled()
            val firstRunTime = scheduler.lastRunTime()!!

            scheduler.runScheduled()

            coVerify { orchestrator.runAutoPipeline("tmpl-1", firstRunTime) }
            coVerify { orchestrator.runAutoPipeline("tmpl-2", firstRunTime) }
        }
    }

    @Nested
    inner class TemplateResolution {

        @Test
        fun `present일 때 use configured templateIds해야 한다`() {
            coEvery { orchestrator.runAutoPipeline(any(), any()) } returns null

            scheduler.runScheduled()

            coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-1", any()) }
            coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-2", any()) }
        }

        @Test
        fun `none configured일 때 fall back to all templates해야 한다`() {
            val propsNoIds = PromptLabProperties(
                schedule = ScheduleProperties(enabled = true, templateIds = emptyList())
            )
            val sch = PromptLabScheduler(orchestrator, promptTemplateStore, propsNoIds)

            every { promptTemplateStore.listTemplates() } returns listOf(
                PromptTemplate(id = "auto-1", name = "Template 1"),
                PromptTemplate(id = "auto-2", name = "Template 2")
            )
            coEvery { orchestrator.runAutoPipeline(any(), any()) } returns null

            sch.runScheduled()

            coVerify(exactly = 1) { orchestrator.runAutoPipeline("auto-1", any()) }
            coVerify(exactly = 1) { orchestrator.runAutoPipeline("auto-2", any()) }
        }
    }

    @Nested
    inner class ErrorIsolation {

        @Test
        fun `one fails일 때 continue with next template해야 한다`() {
            coEvery { orchestrator.runAutoPipeline("tmpl-1", null) } throws RuntimeException("API error")
            coEvery { orchestrator.runAutoPipeline("tmpl-2", null) } returns completedExperiment("tmpl-2")

            scheduler.runScheduled()

            coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-2", null) }
            scheduler.isRunning() shouldBe false // "Should release lock even after error"
        }

        @Test
        fun `release running lock even on unexpected error해야 한다`() {
            coEvery { orchestrator.runAutoPipeline(any(), any()) } throws RuntimeException("fatal")

            scheduler.runScheduled()

            scheduler.isRunning() shouldBe false // "Lock must be released after error"
        }
    }

    @Nested
    inner class ConcurrencyGuard {

        @Test
        fun `prevent concurrent runs해야 한다`() {
            val latch = CountDownLatch(1)
            val started = CountDownLatch(1)

            coEvery { orchestrator.runAutoPipeline("tmpl-1", null) } coAnswers {
                started.countDown()
                latch.await(5, TimeUnit.SECONDS)
                null
            }
            coEvery { orchestrator.runAutoPipeline("tmpl-2", null) } returns null

            val executor = Executors.newFixedThreadPool(2)
            try {
                // 첫 번째 실행이 래치에서 블록됨
                executor.submit { scheduler.runScheduled() }
                started.await(5, TimeUnit.SECONDS)

                // 두 번째 실행은 건너뛰어야 합니다 (이미 실행 중)
                scheduler.isRunning() shouldBe true  // "First run은(는) be in progress"해야 합니다
                scheduler.runScheduled()  // 이것은 즉시 반환되어야 합니다

                // first run를 해제합니다
                latch.countDown()
                executor.shutdown()
                executor.awaitTermination(5, TimeUnit.SECONDS)

                // tmpl-1에 대한 호출 1회만 (두 번째 실행은 완전히 건너뜀)
                coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-1", null) }
            } finally {
                latch.countDown()
                executor.shutdownNow()
            }
        }
    }
}
