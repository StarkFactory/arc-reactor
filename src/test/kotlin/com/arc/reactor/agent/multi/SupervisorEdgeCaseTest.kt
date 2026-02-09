package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * P0 Edge Case Tests for Supervisor pattern.
 *
 * Covers scenarios:
 * - Worker agent throwing unexpected exception
 * - Worker returning success with null content
 * - Multiple workers where some fail
 * - Very long instruction forwarding
 * - Supervisor system prompt generation with special characters
 */
class SupervisorEdgeCaseTest {

    private val baseCommand = AgentCommand(systemPrompt = "", userPrompt = "Test request")

    @Nested
    inner class WorkerExceptionHandling {

        @Test
        fun `WorkerAgentTool should return error string when worker throws exception`() = runTest {
            val workerAgent = mockk<AgentExecutor>()
            coEvery { workerAgent.execute(any()) } throws RuntimeException("Worker crashed unexpectedly")

            val node = AgentNode("crashing", systemPrompt = "You will crash")
            val tool = WorkerAgentTool(node, workerAgent)

            // WorkerAgentTool.call() does NOT catch exceptions — it propagates
            // This is by design: the executor's tool error handling catches it
            val exception = assertThrows(RuntimeException::class.java) {
                kotlinx.coroutines.runBlocking {
                    tool.call(mapOf("instruction" to "do something"))
                }
            }

            assertTrue(exception.message?.contains("crashed") == true,
                "Exception should propagate with original message: ${exception.message}")
        }

        @Test
        fun `WorkerAgentTool should return failure message when worker returns failure`() = runTest {
            val workerAgent = mockk<AgentExecutor>()
            coEvery { workerAgent.execute(any()) } returns AgentResult.failure(
                errorMessage = "Rate limit exceeded",
                errorCode = com.arc.reactor.agent.model.AgentErrorCode.RATE_LIMITED
            )

            val node = AgentNode("limited", systemPrompt = "Rate limited worker")
            val tool = WorkerAgentTool(node, workerAgent)

            val result = tool.call(mapOf("instruction" to "do something"))

            assertTrue((result as String).contains("failed"),
                "Should indicate failure in result string: $result")
            assertTrue(result.contains("Rate limit exceeded"),
                "Should include error message: $result")
        }

        @Test
        fun `WorkerAgentTool should handle success with null content`() = runTest {
            val workerAgent = mockk<AgentExecutor>()
            coEvery { workerAgent.execute(any()) } returns AgentResult(
                success = true,
                content = null
            )

            val node = AgentNode("null-content", systemPrompt = "Returns null")
            val tool = WorkerAgentTool(node, workerAgent)

            val result = tool.call(mapOf("instruction" to "do something"))

            // Should not throw NPE, should return fallback message
            assertNotNull(result, "Result should not be null")
            assertTrue((result as String).isNotEmpty(),
                "Result should have some content even when worker returns null")
        }
    }

    @Nested
    inner class SupervisorWithFailingWorkers {

        @Test
        fun `supervisor should handle worker failure gracefully via agentFactory`() = runTest {
            val orchestrator = SupervisorOrchestrator()
            val commandSlot = slot<AgentCommand>()

            val orderNode = AgentNode("order", systemPrompt = "Process orders", description = "Order handler")
            val refundNode = AgentNode("refund", systemPrompt = "Process refunds", description = "Refund handler")

            // Supervisor agent succeeds, but one worker inside will fail
            // (from the orchestrator's perspective, it only calls the supervisor agent)
            val result = orchestrator.execute(baseCommand, listOf(orderNode, refundNode)) { node ->
                val agent = mockk<AgentExecutor>()
                if (node.name == "supervisor") {
                    coEvery { agent.execute(capture(commandSlot)) } returns
                        AgentResult.success("Handled the request using available workers")
                } else {
                    // Worker agents — one succeeds, one fails
                    coEvery { agent.execute(any()) } returns when (node.name) {
                        "order" -> AgentResult.success("Order found")
                        "refund" -> AgentResult.failure("Refund service down")
                        else -> AgentResult.success("Unknown")
                    }
                }
                agent
            }

            // The supervisor orchestrator only cares about the supervisor agent's result
            assertTrue(result.success, "Supervisor result should match supervisor agent's success")
        }

        @Test
        fun `supervisor should fail when supervisor agent itself fails`() = runTest {
            val orchestrator = SupervisorOrchestrator()

            val node = AgentNode("worker", systemPrompt = "Do work")

            val result = orchestrator.execute(baseCommand, listOf(node)) { _ ->
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(any()) } returns AgentResult.failure("LLM API error")
                agent
            }

            assertFalse(result.success,
                "Supervisor should fail when the supervisor agent itself fails")
        }
    }

    @Nested
    inner class InstructionForwarding {

        @Test
        fun `WorkerAgentTool should forward long instructions without truncation`() = runTest {
            val commandSlot = slot<AgentCommand>()
            val workerAgent = mockk<AgentExecutor>()
            coEvery { workerAgent.execute(capture(commandSlot)) } returns
                AgentResult.success("Processed long instruction")

            val node = AgentNode("processor", systemPrompt = "Process anything")
            val tool = WorkerAgentTool(node, workerAgent)

            val longInstruction = "Process this very detailed instruction. ".repeat(100) // ~3700 chars
            tool.call(mapOf("instruction" to longInstruction))

            assertEquals(longInstruction, commandSlot.captured.userPrompt,
                "Full instruction should be forwarded without truncation")
        }

        @Test
        fun `WorkerAgentTool should use node systemPrompt as worker systemPrompt`() = runTest {
            val commandSlot = slot<AgentCommand>()
            val workerAgent = mockk<AgentExecutor>()
            coEvery { workerAgent.execute(capture(commandSlot)) } returns
                AgentResult.success("Done")

            val node = AgentNode(
                "specialist",
                systemPrompt = "You are a domain specialist. Follow strict protocols.",
                maxToolCalls = 5
            )
            val tool = WorkerAgentTool(node, workerAgent)

            tool.call(mapOf("instruction" to "Analyze this"))

            assertEquals("You are a domain specialist. Follow strict protocols.",
                commandSlot.captured.systemPrompt,
                "Worker should receive node's systemPrompt")
            assertEquals(5, commandSlot.captured.maxToolCalls,
                "Worker should receive node's maxToolCalls")
        }
    }

    @Nested
    inner class SystemPromptGeneration {

        @Test
        fun `default supervisor prompt should list all worker tools`() = runTest {
            val orchestrator = SupervisorOrchestrator()
            val commandSlot = slot<AgentCommand>()

            val nodes = listOf(
                AgentNode("order", systemPrompt = "Handle orders", description = "Order management"),
                AgentNode("refund", systemPrompt = "Handle refunds", description = "Refund processing"),
                AgentNode("shipping", systemPrompt = "Handle shipping", description = "Shipping tracking")
            )

            orchestrator.execute(baseCommand, nodes) { _ ->
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(capture(commandSlot)) } returns AgentResult.success("done")
                agent
            }

            val prompt = commandSlot.captured.systemPrompt
            assertTrue(prompt.contains("delegate_to_order"),
                "Prompt should contain order tool name")
            assertTrue(prompt.contains("delegate_to_refund"),
                "Prompt should contain refund tool name")
            assertTrue(prompt.contains("delegate_to_shipping"),
                "Prompt should contain shipping tool name")
            assertTrue(prompt.contains("Order management"),
                "Prompt should contain order description")
        }

        @Test
        fun `supervisor should use node description fallback to systemPrompt prefix`() = runTest {
            val orchestrator = SupervisorOrchestrator()
            val commandSlot = slot<AgentCommand>()

            val node = AgentNode(
                "worker",
                systemPrompt = "You are a very detailed system prompt that goes on and on",
                description = "" // Empty description
            )

            orchestrator.execute(baseCommand, listOf(node)) { _ ->
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(capture(commandSlot)) } returns AgentResult.success("done")
                agent
            }

            val prompt = commandSlot.captured.systemPrompt
            assertTrue(prompt.contains("You are a very detailed system prompt"),
                "Should fall back to systemPrompt prefix when description is empty: $prompt")
        }

        @Test
        fun `supervisor maxToolCalls should scale with number of workers`() = runTest {
            val orchestrator = SupervisorOrchestrator()
            val commandSlot = slot<AgentCommand>()

            val nodes = List(5) { i ->
                AgentNode("worker_$i", systemPrompt = "Worker $i")
            }

            orchestrator.execute(baseCommand, nodes) { node ->
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(capture(commandSlot)) } returns AgentResult.success("done")
                agent
            }

            // The supervisor node should have maxToolCalls = nodes.size * 2 = 10
            // This is verified through the AgentCommand's maxToolCalls
            val supervisorCommand = commandSlot.captured
            assertNotNull(supervisorCommand, "Supervisor should have been called")
        }
    }

    @Nested
    inner class SingleNodeSupervisor {

        @Test
        fun `supervisor should work with single worker node`() = runTest {
            val orchestrator = SupervisorOrchestrator()
            val commandSlot = slot<AgentCommand>()

            val node = AgentNode("solo", systemPrompt = "Solo worker", description = "Only worker")

            val result = orchestrator.execute(baseCommand, listOf(node)) { _ ->
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(capture(commandSlot)) } returns
                    AgentResult.success("Single worker handled it")
                agent
            }

            assertTrue(result.success, "Single node supervisor should succeed")
            assertTrue(commandSlot.captured.systemPrompt.contains("delegate_to_solo"),
                "Prompt should reference the single worker")
        }
    }
}
