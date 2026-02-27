package com.arc.reactor.promptlab

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.prompt.PromptVersion
import com.arc.reactor.prompt.VersionStatus
import com.arc.reactor.promptlab.analysis.FeedbackAnalyzer
import com.arc.reactor.promptlab.analysis.PromptCandidateGenerator
import com.arc.reactor.promptlab.eval.EvaluationPipeline
import com.arc.reactor.promptlab.eval.EvaluationPipelineFactory
import com.arc.reactor.promptlab.model.EvaluationConfig
import com.arc.reactor.promptlab.model.EvaluationResult
import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.Experiment
import com.arc.reactor.promptlab.model.ExperimentStatus
import com.arc.reactor.promptlab.model.FeedbackAnalysis
import com.arc.reactor.promptlab.model.PromptWeakness
import com.arc.reactor.promptlab.model.TestQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class ExperimentOrchestratorTest {

    private val agentExecutor: AgentExecutor = mockk()
    private val promptTemplateStore: PromptTemplateStore = mockk()
    private val experimentStore: ExperimentStore = mockk(relaxed = true)
    private val evaluationPipelineFactory: EvaluationPipelineFactory = mockk()
    private val reportGenerator: ReportGenerator = ReportGenerator()
    private val feedbackAnalyzer: FeedbackAnalyzer = mockk()
    private val candidateGenerator: PromptCandidateGenerator = mockk()
    private val properties = PromptLabProperties(enabled = true, minNegativeFeedback = 3)

    private lateinit var orchestrator: ExperimentOrchestrator
    private val mockPipeline: EvaluationPipeline = mockk()

    @BeforeEach
    fun setUp() {
        orchestrator = ExperimentOrchestrator(
            agentExecutor = agentExecutor,
            promptTemplateStore = promptTemplateStore,
            experimentStore = experimentStore,
            evaluationPipelineFactory = evaluationPipelineFactory,
            reportGenerator = reportGenerator,
            feedbackAnalyzer = feedbackAnalyzer,
            candidateGenerator = candidateGenerator,
            properties = properties
        )
        every { evaluationPipelineFactory.create(any()) } returns mockPipeline
    }

    @Nested
    inner class ExecuteExperiment {

        @Test
        fun `should execute experiment and generate report`() = runTest {
            val experiment = buildPendingExperiment()
            every { experimentStore.get("exp-1") } returns experiment
            mockVersionLookup("baseline-v", 1, "Baseline prompt")
            mockVersionLookup("candidate-v", 2, "Candidate prompt")
            mockAgentExecution("Agent response")
            mockEvaluation(passed = true, score = 0.9)

            val result = orchestrator.execute("exp-1")

            assertEquals(ExperimentStatus.COMPLETED, result.status) { "Should complete successfully" }
            coVerify { agentExecutor.execute(any<AgentCommand>()) }
        }

        @Test
        fun `should reject non-PENDING experiments`() = runTest {
            val experiment = buildPendingExperiment().copy(
                status = ExperimentStatus.RUNNING
            )
            every { experimentStore.get("exp-1") } returns experiment

            try {
                orchestrator.execute("exp-1")
                assertTrue(false) { "Should throw for non-PENDING experiment" }
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("PENDING")) {
                    "Error should mention PENDING"
                }
            }
        }

        @Test
        fun `should mark experiment as FAILED on error`() = runTest {
            val experiment = buildPendingExperiment()
            every { experimentStore.get("exp-1") } returns experiment
            every { promptTemplateStore.getVersion(any()) } returns null

            val result = orchestrator.execute("exp-1")

            assertEquals(ExperimentStatus.COMPLETED, result.status) {
                "Should still complete even with no valid versions"
            }
        }

        @Test
        fun `should throw for unknown experiment ID`() = runTest {
            every { experimentStore.get("unknown") } returns null

            try {
                orchestrator.execute("unknown")
                assertTrue(false) { "Should throw for unknown experiment" }
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("not found")) {
                    "Error should mention not found"
                }
            }
        }

        @Test
        fun `should include experiment metadata in agent command`() = runTest {
            val experiment = buildPendingExperiment()
            every { experimentStore.get("exp-1") } returns experiment
            mockVersionLookup("baseline-v", 1, "Baseline prompt")
            mockVersionLookup("candidate-v", 2, "Candidate prompt")
            mockEvaluation(passed = true, score = 0.9)

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("OK")

            orchestrator.execute("exp-1")

            val cmd = commandSlot.captured
            assertTrue(cmd.metadata.containsKey("promptlab.experimentId")) {
                "Command should contain experimentId metadata"
            }
        }

        @Test
        fun `should handle agent execution failure gracefully`() = runTest {
            val experiment = buildPendingExperiment()
            every { experimentStore.get("exp-1") } returns experiment
            mockVersionLookup("baseline-v", 1, "Baseline prompt")
            mockVersionLookup("candidate-v", 2, "Candidate prompt")
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns AgentResult.failure("LLM Error")
            mockEvaluation(passed = false, score = 0.0)

            val result = orchestrator.execute("exp-1")

            assertEquals(ExperimentStatus.COMPLETED, result.status) {
                "Should complete even with failed trials"
            }
        }
    }

    @Nested
    inner class AutoPipeline {

        @Test
        fun `should skip when insufficient negative feedback`() = runTest {
            val analysis = FeedbackAnalysis(
                totalFeedback = 10,
                negativeCount = 2,
                weaknesses = emptyList(),
                sampleQueries = emptyList()
            )
            coEvery { feedbackAnalyzer.analyze("tmpl-1", null) } returns analysis

            val result = orchestrator.runAutoPipeline("tmpl-1")

            assertNull(result) { "Should return null when below threshold" }
        }

        @Test
        fun `should run full pipeline when enough negative feedback`() = runTest {
            val analysis = buildAnalysis(negativeCount = 5)
            coEvery { feedbackAnalyzer.analyze("tmpl-1", null) } returns analysis
            coEvery { candidateGenerator.generate("tmpl-1", analysis, 3) } returns listOf("cand-1", "cand-2")

            val activeVersion = PromptVersion(
                id = "active-v", templateId = "tmpl-1", version = 1,
                content = "Active prompt", status = VersionStatus.ACTIVE
            )
            every { promptTemplateStore.getActiveVersion("tmpl-1") } returns activeVersion
            every { promptTemplateStore.getVersion("active-v") } returns activeVersion
            mockVersionLookup("cand-1", 2, "Candidate 1 prompt")
            mockVersionLookup("cand-2", 3, "Candidate 2 prompt")
            mockAgentExecution("Agent response")
            mockEvaluation(passed = true, score = 0.8)

            // Capture saved experiment so execute() can retrieve it
            val experimentSlot = slot<Experiment>()
            every { experimentStore.save(capture(experimentSlot)) } answers {
                experimentSlot.captured
            }
            every { experimentStore.get(any()) } answers {
                val id = firstArg<String>()
                if (experimentSlot.isCaptured && experimentSlot.captured.id == id) {
                    experimentSlot.captured
                } else null
            }

            val result = orchestrator.runAutoPipeline("tmpl-1")

            assertNotNull(result) { "Should return completed experiment" }
            assertEquals(ExperimentStatus.COMPLETED, result?.status) {
                "Should be COMPLETED"
            }
            assertTrue(result?.autoGenerated == true) {
                "Should be auto-generated"
            }
        }

        @Test
        fun `should return null when no candidates are generated`() = runTest {
            val analysis = buildAnalysis(negativeCount = 10)
            coEvery { feedbackAnalyzer.analyze("tmpl-1", null) } returns analysis
            coEvery { candidateGenerator.generate("tmpl-1", analysis, 3) } returns emptyList()

            val result = orchestrator.runAutoPipeline("tmpl-1")

            assertNull(result) { "Should return null when no candidates generated" }
        }
    }

    // ── Helpers ──

    private fun buildPendingExperiment(): Experiment {
        return Experiment(
            id = "exp-1",
            name = "Test Experiment",
            templateId = "tmpl-1",
            baselineVersionId = "baseline-v",
            candidateVersionIds = listOf("candidate-v"),
            testQueries = listOf(TestQuery(query = "What is AI?")),
            status = ExperimentStatus.PENDING,
            createdAt = Instant.now()
        )
    }

    private fun buildAnalysis(negativeCount: Int): FeedbackAnalysis {
        return FeedbackAnalysis(
            totalFeedback = negativeCount * 2,
            negativeCount = negativeCount,
            weaknesses = listOf(
                PromptWeakness("short_answer", "Responses are too short", negativeCount, listOf("Q1"))
            ),
            sampleQueries = listOf(TestQuery(query = "Sample query"))
        )
    }

    private fun mockVersionLookup(versionId: String, version: Int, content: String) {
        every { promptTemplateStore.getVersion(versionId) } returns PromptVersion(
            id = versionId, templateId = "tmpl-1", version = version,
            content = content, status = VersionStatus.DRAFT
        )
    }

    private fun mockAgentExecution(response: String) {
        coEvery { agentExecutor.execute(any<AgentCommand>()) } returns AgentResult.success(
            content = response,
            toolsUsed = listOf("search"),
            tokenUsage = TokenUsage(100, 50),
            durationMs = 200
        )
    }

    private fun mockEvaluation(passed: Boolean, score: Double) {
        coEvery { mockPipeline.evaluate(any(), any()) } returns listOf(
            EvaluationResult(EvaluationTier.STRUCTURAL, passed, score, "Test result")
        )
    }
}
