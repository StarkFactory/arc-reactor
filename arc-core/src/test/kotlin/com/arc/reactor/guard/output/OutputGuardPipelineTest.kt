package com.arc.reactor.guard.output

import com.arc.reactor.agent.model.AgentCommand
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

class OutputGuardPipelineTest {

    private val defaultContext = OutputGuardContext(
        command = AgentCommand(systemPrompt = "Test", userPrompt = "Hello"),
        toolsUsed = emptyList(),
        durationMs = 100
    )

    @Nested
    inner class BasicBehavior {

        @Test
        fun `empty pipeline returns Allowed`() = runTest {
            val pipeline = OutputGuardPipeline(emptyList())
            val result = pipeline.check("some content", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Empty pipeline should return Allowed"
            }
        }

        @Test
        fun `single allowing stage returns Allowed`() = runTest {
            val stage = allowingStage("AllowAll")
            val pipeline = OutputGuardPipeline(listOf(stage))

            val result = pipeline.check("content", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Single allowing stage should return Allowed"
            }
        }

        @Test
        fun `single rejecting stage returns Rejected`() = runTest {
            val stage = rejectingStage("BlockAll", "blocked")
            val pipeline = OutputGuardPipeline(listOf(stage))

            val result = pipeline.check("content", defaultContext)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Single rejecting stage should return Rejected"
            }
            assertEquals("blocked", rejected.reason) { "Rejection reason should match" }
            assertEquals("BlockAll", rejected.stage) { "Stage name should be set" }
        }

        @Test
        fun `single modifying stage returns Modified`() = runTest {
            val stage = modifyingStage("Masker", "masked content", "PII found")
            val pipeline = OutputGuardPipeline(listOf(stage))

            val result = pipeline.check("original", defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Single modifying stage should return Modified"
            }
            assertEquals("masked content", modified.content) { "Modified content should match" }
            assertEquals("PII found", modified.reason) { "Modification reason should match" }
            assertEquals("Masker", modified.stage) { "Stage name should be set" }
        }
    }

    @Nested
    inner class StageOrdering {

        @Test
        fun `stages execute in ascending order`() = runTest {
            val executionOrder = mutableListOf<String>()
            val stages = listOf(
                trackingStage("Third", 30, executionOrder),
                trackingStage("First", 10, executionOrder),
                trackingStage("Second", 20, executionOrder)
            )
            val pipeline = OutputGuardPipeline(stages)

            pipeline.check("content", defaultContext)
            assertEquals(listOf("First", "Second", "Third"), executionOrder) {
                "Stages should execute in ascending order"
            }
        }

        @Test
        fun `disabled stages are skipped`() = runTest {
            val executionOrder = mutableListOf<String>()
            val stages = listOf(
                trackingStage("Active", 10, executionOrder),
                trackingStage("Disabled", 20, executionOrder, enabled = false),
                trackingStage("AlsoActive", 30, executionOrder)
            )
            val pipeline = OutputGuardPipeline(stages)

            pipeline.check("content", defaultContext)
            assertEquals(listOf("Active", "AlsoActive"), executionOrder) {
                "Disabled stages should be skipped"
            }
        }

        @Test
        fun `size reflects only enabled stages`() {
            val stages = listOf(
                allowingStage("A", order = 10),
                allowingStage("B", order = 20, enabled = false),
                allowingStage("C", order = 30)
            )
            val pipeline = OutputGuardPipeline(stages)
            assertEquals(2, pipeline.size) { "Size should count only enabled stages" }
        }
    }

    @Nested
    inner class PipelineFlow {

        @Test
        fun `rejection stops pipeline immediately`() = runTest {
            val executionOrder = mutableListOf<String>()
            val stages = listOf(
                trackingStage("First", 10, executionOrder),
                rejectingStage("Blocker", "blocked", order = 20),
                trackingStage("NeverReached", 30, executionOrder)
            )
            val pipeline = OutputGuardPipeline(stages)

            val result = pipeline.check("content", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Should be rejected"
            }
            assertEquals(listOf("First"), executionOrder) {
                "Stages after rejection should not execute"
            }
        }

        @Test
        fun `modification propagates to next stage`() = runTest {
            val receivedContent = mutableListOf<String>()
            val stages = listOf(
                object : OutputGuardStage {
                    override val stageName = "Modifier"
                    override val order = 10
                    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                        receivedContent.add(content)
                        return OutputGuardResult.Modified(content = "modified", reason = "test")
                    }
                },
                object : OutputGuardStage {
                    override val stageName = "Checker"
                    override val order = 20
                    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                        receivedContent.add(content)
                        return OutputGuardResult.Allowed.DEFAULT
                    }
                }
            )
            val pipeline = OutputGuardPipeline(stages)

            val result = pipeline.check("original", defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should return Modified from first stage"
            }
            assertEquals("modified", modified.content) { "Final content should be modified" }
            assertEquals(listOf("original", "modified"), receivedContent) {
                "Second stage should receive modified content from first stage"
            }
        }

        @Test
        fun `multiple modifications chain correctly`() = runTest {
            val stages = listOf(
                modifyingStage("First", "step1", "reason1", order = 10),
                modifyingStage("Second", "step2", "reason2", order = 20)
            )
            val pipeline = OutputGuardPipeline(stages)

            val result = pipeline.check("original", defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should return last Modified result"
            }
            assertEquals("step2", modified.content) { "Should contain last modification" }
            assertEquals("Second", modified.stage) { "Stage should be last modifier" }
        }
    }

    @Nested
    inner class FailClose {

        @Test
        fun `exception causes rejection (fail-close)`() = runTest {
            val stage = object : OutputGuardStage {
                override val stageName = "Crasher"
                override val order = 10
                override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                    throw RuntimeException("Unexpected error")
                }
            }
            val pipeline = OutputGuardPipeline(listOf(stage))

            val result = pipeline.check("content", defaultContext)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Exception should cause rejection (fail-close)"
            }
            assertEquals(OutputRejectionCategory.SYSTEM_ERROR, rejected.category) {
                "Category should be SYSTEM_ERROR"
            }
            assertEquals("Crasher", rejected.stage) { "Stage name should be set" }
        }

        @Test
        fun `CancellationException is rethrown, not caught`() = runTest {
            val stage = object : OutputGuardStage {
                override val stageName = "Canceller"
                override val order = 10
                override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                    throw CancellationException("Cancelled")
                }
            }
            val pipeline = OutputGuardPipeline(listOf(stage))

            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.test.runTest {
                    pipeline.check("content", defaultContext)
                }
            }
        }
    }

    // -- Helper functions --

    private fun allowingStage(name: String, order: Int = 100, enabled: Boolean = true) = object : OutputGuardStage {
        override val stageName = name
        override val order = order
        override val enabled = enabled
        override suspend fun check(content: String, context: OutputGuardContext) = OutputGuardResult.Allowed.DEFAULT
    }

    private fun rejectingStage(name: String, reason: String, order: Int = 100) = object : OutputGuardStage {
        override val stageName = name
        override val order = order
        override suspend fun check(content: String, context: OutputGuardContext) = OutputGuardResult.Rejected(
            reason = reason,
            category = OutputRejectionCategory.POLICY_VIOLATION
        )
    }

    private fun modifyingStage(name: String, newContent: String, reason: String, order: Int = 100) =
        object : OutputGuardStage {
            override val stageName = name
            override val order = order
            override suspend fun check(content: String, context: OutputGuardContext) =
                OutputGuardResult.Modified(content = newContent, reason = reason)
        }

    private fun trackingStage(
        name: String, order: Int, tracker: MutableList<String>, enabled: Boolean = true
    ) = object : OutputGuardStage {
        override val stageName = name
        override val order = order
        override val enabled = enabled
        override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
            tracker.add(name)
            return OutputGuardResult.Allowed.DEFAULT
        }
    }
}
