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
    inner class MetadataPropagation {

        @Test
        fun `WorkerAgentTool should propagate parent metadata to worker command`() = runTest {
            val commandSlot = slot<AgentCommand>()
            val workerAgent = mockk<AgentExecutor>()
            coEvery { workerAgent.execute(capture(commandSlot)) } returns
                AgentResult.success("done")

            val parentCommand = AgentCommand(
                systemPrompt = "supervisor",
                userPrompt = "original request",
                userId = "user-42",
                metadata = mapOf("tenantId" to "acme-corp", "sessionId" to "sess-1", "channel" to "slack")
            )

            val node = AgentNode("worker", systemPrompt = "Do work")
            val tool = WorkerAgentTool(node, workerAgent, parentCommand = parentCommand)

            tool.call(mapOf("instruction" to "process this"))

            val captured = commandSlot.captured
            assertEquals("acme-corp", captured.metadata["tenantId"],
                "Worker should inherit parent tenantId")
            assertEquals("sess-1", captured.metadata["sessionId"],
                "Worker should inherit parent sessionId")
            assertEquals("slack", captured.metadata["channel"],
                "Worker should inherit parent channel")
            assertEquals("user-42", captured.userId,
                "Worker should inherit parent userId")
        }

        @Test
        fun `WorkerAgentTool without parentCommand should have empty metadata`() = runTest {
            val commandSlot = slot<AgentCommand>()
            val workerAgent = mockk<AgentExecutor>()
            coEvery { workerAgent.execute(capture(commandSlot)) } returns
                AgentResult.success("done")

            val node = AgentNode("worker", systemPrompt = "Do work")
            val tool = WorkerAgentTool(node, workerAgent) // no parentCommand

            tool.call(mapOf("instruction" to "process this"))

            assertTrue(commandSlot.captured.metadata.isEmpty(),
                "Without parentCommand, metadata should be empty")
            assertNull(commandSlot.captured.userId,
                "Without parentCommand, userId should be null")
        }

        @Test
        fun `SupervisorOrchestrator should propagate metadata through WorkerAgentTools`() = runTest {
            val orchestrator = SupervisorOrchestrator()
            val parentCommand = AgentCommand(
                systemPrompt = "",
                userPrompt = "환불 처리",
                userId = "user-99",
                metadata = mapOf("tenantId" to "enterprise-corp", "channel" to "web")
            )

            val workerNode = AgentNode("refund", systemPrompt = "환불 처리", description = "환불")

            // Capture both the supervisor command and the worker command
            val supervisorCommandSlot = slot<AgentCommand>()

            orchestrator.execute(parentCommand, listOf(workerNode)) { node ->
                val agent = mockk<AgentExecutor>()
                if (node.name == "supervisor") {
                    coEvery { agent.execute(capture(supervisorCommandSlot)) } returns
                        AgentResult.success("done")
                } else {
                    coEvery { agent.execute(any()) } returns AgentResult.success("worker done")
                }
                agent
            }

            // Supervisor itself should inherit metadata via command.copy()
            val supervisorCmd = supervisorCommandSlot.captured
            assertEquals("enterprise-corp", supervisorCmd.metadata["tenantId"],
                "Supervisor command should carry parent tenantId")
            assertEquals("user-99", supervisorCmd.userId,
                "Supervisor command should carry parent userId")
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
