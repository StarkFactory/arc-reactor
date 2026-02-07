package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import io.mockk.coEvery
import io.mockk.mockk
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
        fun `should chain outputs through sequential nodes`() = runTest {
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
        fun `should pass previous output as next input`() = runTest {
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
        fun `should execute single node successfully`() = runTest {
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
        fun `should stop on intermediate failure`() = runTest {
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
        fun `should return failure for empty nodes`() = runTest {
            val result = orchestrator.execute(baseCommand, emptyList()) { mockAgent("") }

            assertFalse(result.success, "Should fail for empty nodes")
        }
    }

    @Nested
    inner class Metadata {

        @Test
        fun `should track duration`() = runTest {
            val node = AgentNode("A", systemPrompt = "")
            val result = orchestrator.execute(baseCommand, listOf(node)) {
                mockAgent("done")
            }

            assertTrue(result.totalDurationMs >= 0, "Duration should be non-negative")
            assertTrue(result.nodeResults[0].durationMs >= 0, "Node duration should be non-negative")
        }
    }
}
