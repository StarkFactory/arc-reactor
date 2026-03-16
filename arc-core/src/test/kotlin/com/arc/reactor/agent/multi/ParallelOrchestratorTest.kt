package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ParallelOrchestratorTest {

    private val baseCommand = AgentCommand(systemPrompt = "", userPrompt = "analyze this")

    private fun mockAgent(response: String, success: Boolean = true, delayMs: Long = 0): AgentExecutor {
        val agent = mockk<AgentExecutor>()
        coEvery { agent.execute(any()) } coAnswers {
            if (delayMs > 0) delay(delayMs)
            if (success) AgentResult.success(response) else AgentResult.failure(response)
        }
        return agent
    }

    @Nested
    inner class NormalFlow {

        @Test
        fun `execute all nodes and merge results해야 한다`() = runTest {
            val orchestrator = ParallelOrchestrator()
            val nodes = listOf(
                AgentNode("security", systemPrompt = ""),
                AgentNode("style", systemPrompt = ""),
                AgentNode("logic", systemPrompt = "")
            )

            val result = orchestrator.execute(baseCommand, nodes) { node ->
                mockAgent("${node.name} analysis done")
            }

            assertTrue(result.success, "Parallel execution should succeed")
            assertEquals(3, result.nodeResults.size, "Should have 3 node results")
            assertTrue(
                result.finalResult.content.orEmpty().contains("security"),
                "Merged result should contain security output"
            )
            assertTrue(
                result.finalResult.content.orEmpty().contains("logic"),
                "Merged result should contain logic output"
            )
        }

        @Test
        fun `execute nodes concurrently해야 한다`() = runTest {
            val orchestrator = ParallelOrchestrator()
            val concurrentCount = AtomicInteger(0)
            val maxConcurrent = AtomicInteger(0)

            val nodes = listOf(
                AgentNode("A", systemPrompt = ""),
                AgentNode("B", systemPrompt = ""),
                AgentNode("C", systemPrompt = "")
            )

            orchestrator.execute(baseCommand, nodes) { node ->
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(any()) } coAnswers {
                    val current = concurrentCount.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    delay(50)
                    concurrentCount.decrementAndGet()
                    AgentResult.success("${node.name} done")
                }
                agent
            }

            assertTrue(
                maxConcurrent.get() > 1,
                "Should execute concurrently, maxConcurrent=${maxConcurrent.get()}"
            )
        }
    }

    @Nested
    inner class FailureHandling {

        @Test
        fun `failFast is false일 때 succeed with partial failures해야 한다`() = runTest {
            val orchestrator = ParallelOrchestrator(failFast = false)
            val nodes = listOf(
                AgentNode("A", systemPrompt = ""),
                AgentNode("B", systemPrompt = "")
            )

            val result = orchestrator.execute(baseCommand, nodes) { node ->
                if (node.name == "A") mockAgent("A success") else mockAgent("B failed", success = false)
            }

            assertTrue(result.success, "Should succeed when failFast=false and at least one succeeds")
            assertTrue(
                result.finalResult.content.orEmpty().contains("A success"),
                "Merged result should contain successful node output"
            )
        }

        @Test
        fun `failFast is true일 때 fail with partial failures해야 한다`() = runTest {
            val orchestrator = ParallelOrchestrator(failFast = true)
            val nodes = listOf(
                AgentNode("A", systemPrompt = ""),
                AgentNode("B", systemPrompt = "")
            )

            val result = orchestrator.execute(baseCommand, nodes) { node ->
                if (node.name == "A") mockAgent("A ok") else mockAgent("B error", success = false)
            }

            assertFalse(result.success, "Should fail when failFast=true and any node fails")
        }

        @Test
        fun `empty nodes에 대해 return failure해야 한다`() = runTest {
            val orchestrator = ParallelOrchestrator()
            val result = orchestrator.execute(baseCommand, emptyList()) { mockAgent("") }

            assertFalse(result.success, "Should fail for empty nodes")
        }

        @Test
        fun `rethrow cancellation exception from node해야 한다`() {
            val orchestrator = ParallelOrchestrator()
            val cancellingAgent = mockk<AgentExecutor>()
            coEvery { cancellingAgent.execute(any()) } throws CancellationException("cancelled")

            assertThrows(CancellationException::class.java) {
                runBlocking {
                    orchestrator.execute(
                        command = baseCommand,
                        nodes = listOf(AgentNode("cancel-node", systemPrompt = ""))
                    ) { cancellingAgent }
                }
            }
        }
    }

    @Nested
    inner class PerNodeTimeout {

        @Test
        fun `timeout slow node while fast node succeeds해야 한다`() = runTest {
            val orchestrator = ParallelOrchestrator(failFast = false)
            val nodes = listOf(
                AgentNode("fast", systemPrompt = "", timeoutMs = 5000),
                AgentNode("slow", systemPrompt = "", timeoutMs = 100)
            )

            val result = orchestrator.execute(baseCommand, nodes) { node ->
                if (node.name == "fast") {
                    mockAgent("fast result")
                } else {
                    mockAgent("should not reach", delayMs = 500)
                }
            }

            assertTrue(result.success, "Should succeed when at least one node completes (failFast=false)")
            assertTrue(
                result.finalResult.content.orEmpty().contains("fast result"),
                "Should contain fast node's result"
            )
        }

        @Test
        fun `node timeout is null일 때 use default timeout해야 한다`() = runTest {
            val orchestrator = ParallelOrchestrator()
            val nodes = listOf(
                AgentNode("A", systemPrompt = "", timeoutMs = null)
            )

            val result = orchestrator.execute(baseCommand, nodes) { mockAgent("quick") }

            assertTrue(result.success, "Should succeed with default timeout when node completes quickly")
        }
    }

    @Nested
    inner class ResultMerging {

        @Test
        fun `use custom merger해야 한다`() = runTest {
            val customMerger = ResultMerger { results ->
                results.joinToString(" | ") { it.result.content ?: "" }
            }
            val orchestrator = ParallelOrchestrator(merger = customMerger)

            val nodes = listOf(
                AgentNode("A", systemPrompt = ""),
                AgentNode("B", systemPrompt = "")
            )

            val result = orchestrator.execute(baseCommand, nodes) { node ->
                mockAgent("${node.name}-result")
            }

            assertEquals(
                "A-result | B-result",
                result.finalResult.content,
                "Should use custom merger format"
            )
        }
    }
}
