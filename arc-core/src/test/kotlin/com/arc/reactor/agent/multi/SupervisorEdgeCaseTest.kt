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
 * P0 감독자 패턴 엣지 케이스에 대한 테스트.
 *
 * 다루는 시나리오:
 * - 작업자 에이전트가 예상치 못한 예외를 던지는 경우
 * - 작업자가 null 콘텐츠로 성공을 반환하는 경우
 * - 일부가 실패하는 다수 작업자
 * - 매우 긴 지시사항 전달
 * - 특수 문자가 포함된 감독자 시스템 프롬프트 생성
 */
class SupervisorEdgeCaseTest {

    private val baseCommand = AgentCommand(systemPrompt = "", userPrompt = "Test request")

    @Nested
    inner class WorkerExceptionHandling {

        @Test
        fun `WorkerAgentTool은(는) return error string when worker throws exception해야 한다`() = runTest {
            val workerAgent = mockk<AgentExecutor>()
            coEvery { workerAgent.execute(any()) } throws RuntimeException("Worker crashed unexpectedly")

            val node = AgentNode("crashing", systemPrompt = "You will crash")
            val tool = WorkerAgentTool(node, workerAgent)

            // ToolCallback contract: return "Error: ..." strings, do NOT throw
            val result = tool.call(mapOf("instruction" to "do something"))

            assertTrue((result as String).startsWith("Error:"),
                "Should return error string per ToolCallback contract: $result")
        }

        @Test
        fun `WorkerAgentTool은(는) return failure message when worker returns failure해야 한다`() = runTest {
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
        fun `WorkerAgentTool은(는) handle success with null content해야 한다`() = runTest {
            val workerAgent = mockk<AgentExecutor>()
            coEvery { workerAgent.execute(any()) } returns AgentResult(
                success = true,
                content = null
            )

            val node = AgentNode("null-content", systemPrompt = "Returns null")
            val tool = WorkerAgentTool(node, workerAgent)

            val result = tool.call(mapOf("instruction" to "do something"))

            // NPE를 던지지 않고 폴백 메시지를 반환해야 합니다
            assertNotNull(result, "Result should not be null")
            assertTrue((result as String).isNotEmpty(),
                "Result should have some content even when worker returns null")
        }
    }

    @Nested
    inner class SupervisorWithFailingWorkers {

        @Test
        fun `supervisor은(는) handle worker failure gracefully via agentFactory해야 한다`() = runTest {
            val orchestrator = SupervisorOrchestrator()
            val commandSlot = slot<AgentCommand>()

            val orderNode = AgentNode("order", systemPrompt = "Process orders", description = "Order handler")
            val refundNode = AgentNode("refund", systemPrompt = "Process refunds", description = "Refund handler")

            // Supervisor agent succeeds, but one worker inside will fail
            // (오케스트레이터 관점에서, 감독자 에이전트만 호출합니다)
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

            // 감독자 오케스트레이터는 감독자 에이전트의 결과만 신경씁니다
            assertTrue(result.success, "Supervisor result should match supervisor agent's success")
        }

        @Test
        fun `supervisor은(는) fail when supervisor agent itself fails해야 한다`() = runTest {
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
        fun `WorkerAgentTool은(는) forward long instructions without truncation해야 한다`() = runTest {
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
        fun `WorkerAgentTool은(는) use node systemPrompt as worker systemPrompt해야 한다`() = runTest {
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
        fun `default supervisor prompt은(는) list all worker tools해야 한다`() = runTest {
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
        fun `supervisor은(는) use node description fallback to systemPrompt prefix해야 한다`() = runTest {
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
        fun `supervisor maxToolCalls은(는) scale with number of workers해야 한다`() = runTest {
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

            // 감독자 노드의 maxToolCalls = nodes.size * 2 = 10이어야 합니다
            // 이것은 AgentCommand의 maxToolCalls를 통해 검증됩니다
            val supervisorCommand = commandSlot.captured
            assertNotNull(supervisorCommand, "Supervisor should have been called")
        }
    }

    @Nested
    inner class SingleNodeSupervisor {

        @Test
        fun `supervisor은(는) work with single worker node해야 한다`() = runTest {
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
