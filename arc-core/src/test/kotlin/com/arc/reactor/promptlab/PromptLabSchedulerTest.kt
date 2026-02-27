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
        fun `should run pipeline for each configured template`() {
            coEvery { orchestrator.runAutoPipeline("tmpl-1", null) } returns completedExperiment("tmpl-1")
            coEvery { orchestrator.runAutoPipeline("tmpl-2", null) } returns completedExperiment("tmpl-2")

            scheduler.runScheduled()

            coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-1", null) }
            coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-2", null) }
        }

        @Test
        fun `should handle null result from pipeline gracefully`() {
            coEvery { orchestrator.runAutoPipeline("tmpl-1", null) } returns null
            coEvery { orchestrator.runAutoPipeline("tmpl-2", null) } returns null

            scheduler.runScheduled()

            scheduler.isRunning() shouldBe false // "Should not be running after completion"
        }

        @Test
        fun `should update lastRunTime after completion`() {
            coEvery { orchestrator.runAutoPipeline(any(), any()) } returns null
            scheduler.lastRunTime() shouldBe null // "Should be null before first run"

            scheduler.runScheduled()

            scheduler.lastRunTime() shouldNotBe null // "Should be set after run"
        }

        @Test
        fun `should pass since parameter on subsequent runs`() {
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
        fun `should use configured templateIds when present`() {
            coEvery { orchestrator.runAutoPipeline(any(), any()) } returns null

            scheduler.runScheduled()

            coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-1", any()) }
            coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-2", any()) }
        }

        @Test
        fun `should fall back to all templates when none configured`() {
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
        fun `should continue with next template when one fails`() {
            coEvery { orchestrator.runAutoPipeline("tmpl-1", null) } throws RuntimeException("API error")
            coEvery { orchestrator.runAutoPipeline("tmpl-2", null) } returns completedExperiment("tmpl-2")

            scheduler.runScheduled()

            coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-2", null) }
            scheduler.isRunning() shouldBe false // "Should release lock even after error"
        }

        @Test
        fun `should release running lock even on unexpected error`() {
            coEvery { orchestrator.runAutoPipeline(any(), any()) } throws RuntimeException("fatal")

            scheduler.runScheduled()

            scheduler.isRunning() shouldBe false // "Lock must be released after error"
        }
    }

    @Nested
    inner class ConcurrencyGuard {

        @Test
        fun `should prevent concurrent runs`() {
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
                // First run blocks on latch
                executor.submit { scheduler.runScheduled() }
                started.await(5, TimeUnit.SECONDS)

                // Second run should skip (already running)
                scheduler.isRunning() shouldBe true // "First run should be in progress"
                scheduler.runScheduled() // This should return immediately

                // Release first run
                latch.countDown()
                executor.shutdown()
                executor.awaitTermination(5, TimeUnit.SECONDS)

                // Only 1 call to tmpl-1 (second run skipped entirely)
                coVerify(exactly = 1) { orchestrator.runAutoPipeline("tmpl-1", null) }
            } finally {
                latch.countDown()
                executor.shutdownNow()
            }
        }
    }
}
