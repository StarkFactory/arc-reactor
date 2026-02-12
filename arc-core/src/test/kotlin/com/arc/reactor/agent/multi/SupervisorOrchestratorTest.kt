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

class SupervisorOrchestratorTest {

    private val baseCommand = AgentCommand(systemPrompt = "", userPrompt = "주문 환불해주세요")

    @Nested
    inner class SupervisorSetup {

        @Test
        fun `should create worker tools for supervisor agent`() = runTest {
            val orchestrator = SupervisorOrchestrator()
            val commandSlot = slot<AgentCommand>()

            val orderNode = AgentNode("order", systemPrompt = "주문 처리", description = "주문 조회 및 변경")
            val refundNode = AgentNode("refund", systemPrompt = "환불 처리", description = "환불 신청 및 확인")

            orchestrator.execute(baseCommand, listOf(orderNode, refundNode)) { node ->
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(capture(commandSlot)) } returns
                    AgentResult.success("supervisor completed")
                agent
            }

            // Supervisor's system prompt should contain the worker list
            val supervisorPrompt = commandSlot.captured.systemPrompt
            assertTrue(
                supervisorPrompt.contains("delegate_to_order"),
                "Supervisor prompt should list order worker tool"
            )
            assertTrue(
                supervisorPrompt.contains("delegate_to_refund"),
                "Supervisor prompt should list refund worker tool"
            )
        }

        @Test
        fun `should use custom supervisor system prompt`() = runTest {
            val customPrompt = "너는 고객 상담 매니저야. 적절한 팀에 전달해."
            val orchestrator = SupervisorOrchestrator(supervisorSystemPrompt = customPrompt)
            val commandSlot = slot<AgentCommand>()

            val node = AgentNode("team", systemPrompt = "")

            orchestrator.execute(baseCommand, listOf(node)) { _ ->
                val agent = mockk<AgentExecutor>()
                coEvery { agent.execute(capture(commandSlot)) } returns AgentResult.success("done")
                agent
            }

            assertEquals(customPrompt, commandSlot.captured.systemPrompt, "Should use custom supervisor prompt")
        }

        @Test
        fun `should return failure for empty nodes`() = runTest {
            val orchestrator = SupervisorOrchestrator()
            val result = orchestrator.execute(baseCommand, emptyList()) { node ->
                mockk<AgentExecutor>()
            }

            assertFalse(result.success, "Should fail for empty nodes")
        }
    }

    @Nested
    inner class WorkerAgentToolTest {

        @Test
        fun `should delegate instruction to worker agent`() = runTest {
            val commandSlot = slot<AgentCommand>()
            val workerAgent = mockk<AgentExecutor>()
            coEvery { workerAgent.execute(capture(commandSlot)) } returns
                AgentResult.success("환불 처리 완료")

            val node = AgentNode("refund", systemPrompt = "환불 정책에 따라 처리하라", description = "환불 처리")
            val tool = WorkerAgentTool(node, workerAgent)

            val result = tool.call(mapOf("instruction" to "ORD-123 주문 환불 처리해줘"))

            assertEquals("환불 처리 완료", result, "Should return worker agent's response")
            assertEquals("ORD-123 주문 환불 처리해줘", commandSlot.captured.userPrompt, "Should pass instruction as userPrompt")
            assertEquals("환불 정책에 따라 처리하라", commandSlot.captured.systemPrompt, "Should use node's systemPrompt")
        }

        @Test
        fun `should return error when worker fails`() = runTest {
            val workerAgent = mockk<AgentExecutor>()
            coEvery { workerAgent.execute(any()) } returns AgentResult.failure("서비스 오류")

            val node = AgentNode("worker", systemPrompt = "")
            val tool = WorkerAgentTool(node, workerAgent)

            val result = tool.call(mapOf("instruction" to "do something"))

            assertTrue(
                (result as String).contains("failed"),
                "Should indicate failure: $result"
            )
        }

        @Test
        fun `should return error when instruction is missing`() = runTest {
            val workerAgent = mockk<AgentExecutor>()
            val node = AgentNode("worker", systemPrompt = "")
            val tool = WorkerAgentTool(node, workerAgent)

            val result = tool.call(emptyMap())

            assertTrue(
                (result as String).contains("Error"),
                "Should return error for missing instruction"
            )
        }

        @Test
        fun `should have correct tool name and description`() {
            val workerAgent = mockk<AgentExecutor>()
            val node = AgentNode("refund", systemPrompt = "", description = "환불 처리 담당")
            val tool = WorkerAgentTool(node, workerAgent)

            assertEquals("delegate_to_refund", tool.name, "Tool name should be delegate_to_{node.name}")
            assertTrue(
                tool.description.contains("환불 처리 담당"),
                "Tool description should include node description"
            )
        }
    }

    @Nested
    inner class BuilderIntegration {

        @Test
        fun `should build sequential pipeline via builder`() = runTest {
            val result = MultiAgent.sequential()
                .node("A") {
                    systemPrompt = "You are A"
                }
                .node("B") {
                    systemPrompt = "You are B"
                }
                .execute(baseCommand) { node ->
                    val agent = mockk<AgentExecutor>()
                    coEvery { agent.execute(any()) } returns AgentResult.success("${node.name} done")
                    agent
                }

            assertTrue(result.success, "Builder sequential should succeed")
            assertEquals(2, result.nodeResults.size, "Should have 2 node results")
        }

        @Test
        fun `should build parallel pipeline via builder`() = runTest {
            val result = MultiAgent.parallel()
                .node("X") { systemPrompt = "" }
                .node("Y") { systemPrompt = "" }
                .execute(baseCommand) { node ->
                    val agent = mockk<AgentExecutor>()
                    coEvery { agent.execute(any()) } returns AgentResult.success("${node.name} result")
                    agent
                }

            assertTrue(result.success, "Builder parallel should succeed")
            assertEquals(2, result.nodeResults.size, "Should have 2 node results")
        }
    }
}
