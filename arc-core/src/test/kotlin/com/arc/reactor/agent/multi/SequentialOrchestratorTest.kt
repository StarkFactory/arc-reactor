package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SequentialOrchestratorTest {

    private val orchestrator = SequentialOrchestrator()
    private val baseCommand = AgentCommand(systemPrompt = "", userPrompt = "initial input")

    private fun mockAgent(response: String, success: Boolean = true): AgentExecutor {
        val agent = mockk<AgentExecutor>()
        coEvery { agent.execute(any()) } returns if (success) {
            AgentResult.success(response)
        } else {
            AgentResult.failure(response)
        }
        return agent
    }

    @Nested
    inner class NormalFlow {

        @Test
        fun `chain outputs through sequential nodes해야 한다`() = runTest {
            val agents = mutableListOf<AgentExecutor>()

            val nodeA = AgentNode("A", systemPrompt = "You are A")
            val nodeB = AgentNode("B", systemPrompt = "You are B")

            val result = orchestrator.execute(baseCommand, listOf(nodeA, nodeB)) { node ->
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(any()) } answers {
                    val cmd = firstArg<AgentCommand>()
                    if (node.name == "A") {
                        AgentResult.success("output from A based on: ${cmd.userPrompt}")
                    } else {
                        AgentResult.success("output from B based on: ${cmd.userPrompt}")
                    }
                }
                agents.add(agent)
                agent
            }

            assertTrue(result.success, "Sequential execution should succeed")
            assertEquals(2, result.nodeResults.size, "Should have 2 node results")
            assertTrue(
                result.finalResult.content!!.contains("output from B"),
                "Final result should be from node B"
            )
        }

        @Test
        fun `pass previous output as next input해야 한다`() = runTest {
            var capturedInput = ""
            val nodeA = AgentNode("A", systemPrompt = "")
            val nodeB = AgentNode("B", systemPrompt = "")

            orchestrator.execute(baseCommand, listOf(nodeA, nodeB)) { node ->
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(any()) } answers {
                    val cmd = firstArg<AgentCommand>()
                    if (node.name == "A") {
                        AgentResult.success("transformed data")
                    } else {
                        capturedInput = cmd.userPrompt
                        AgentResult.success("final")
                    }
                }
                agent
            }

            assertEquals("transformed data", capturedInput, "Node B should receive node A's output as input")
        }

        @Test
        fun `execute single node successfully해야 한다`() = runTest {
            val node = AgentNode("only", systemPrompt = "")
            val result = orchestrator.execute(baseCommand, listOf(node)) {
                mockAgent("single result")
            }

            assertTrue(result.success, "Single node execution should succeed")
            assertEquals("single result", result.finalResult.content, "Should return single node's output")
            assertEquals(1, result.nodeResults.size, "Should have 1 node result")
        }
    }

    @Nested
    inner class FailureHandling {

        @Test
        fun `stop on intermediate failure해야 한다`() = runTest {
            val nodeA = AgentNode("A", systemPrompt = "")
            val nodeB = AgentNode("B", systemPrompt = "")
            val nodeC = AgentNode("C", systemPrompt = "")
            var cExecuted = false

            val result = orchestrator.execute(baseCommand, listOf(nodeA, nodeB, nodeC)) { node ->
                when (node.name) {
                    "A" -> mockAgent("ok")
                    "B" -> mockAgent("B failed", success = false)
                    else -> {
                        cExecuted = true
                        mockAgent("should not reach")
                    }
                }
            }

            assertFalse(result.success, "Should fail when intermediate node fails")
            assertEquals(2, result.nodeResults.size, "Should have results for A and B only")
            assertFalse(cExecuted, "Node C should not be executed after B fails")
        }

        @Test
        fun `empty nodes에 대해 return failure해야 한다`() = runTest {
            val result = orchestrator.execute(baseCommand, emptyList()) { mockAgent("") }

            assertFalse(result.success, "Should fail for empty nodes")
        }
    }

    @Nested
    inner class Metadata {

        @Test
        fun `track duration해야 한다`() = runTest {
            val node = AgentNode("A", systemPrompt = "")
            val result = orchestrator.execute(baseCommand, listOf(node)) {
                mockAgent("done")
            }

            assertTrue(result.totalDurationMs >= 0, "Duration should be non-negative")
            assertTrue(result.nodeResults[0].durationMs >= 0, "Node duration should be non-negative")
        }
    }

    @Nested
    inner class PerNodeTimeout {

        @Test
        fun `node exceeds its timeout일 때 return failure해야 한다`() = runTest {
            val node = AgentNode("slow", systemPrompt = "", timeoutMs = 100)

            val result = orchestrator.execute(baseCommand, listOf(node)) {
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(any()) } coAnswers {
                    delay(500)
                    AgentResult.success("should not reach")
                }
                agent
            }

            assertFalse(result.success, "Should fail when node exceeds its timeout")
            assertTrue(
                result.finalResult.errorMessage.orEmpty().contains("timed out"),
                "Error message should indicate timeout: ${result.finalResult.errorMessage}"
            )
        }

        @Test
        fun `node timeout is null일 때 use global default timeout해야 한다`() = runTest {
            val node = AgentNode("fast", systemPrompt = "", timeoutMs = null)

            val result = orchestrator.execute(baseCommand, listOf(node)) {
                mockAgent("quick result")
            }

            assertTrue(result.success, "Should succeed with default timeout when node completes quickly")
        }

        @Test
        fun `allow different timeouts per node해야 한다`() = runTest {
            val fastNode = AgentNode("fast", systemPrompt = "", timeoutMs = 5000)
            val slowNode = AgentNode("slow", systemPrompt = "", timeoutMs = 5000)

            val result = orchestrator.execute(baseCommand, listOf(fastNode, slowNode)) { node ->
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(any()) } returns AgentResult.success("${node.name} done")
                agent
            }

            assertTrue(result.success, "Both nodes should complete within their timeouts")
            assertEquals(2, result.nodeResults.size, "Should have 2 node results")
        }
    }
}
