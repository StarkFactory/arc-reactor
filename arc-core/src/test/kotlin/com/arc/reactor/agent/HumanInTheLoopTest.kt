package com.arc.reactor.agent

import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.approval.InMemoryPendingApprovalStore
import com.arc.reactor.approval.ToolNameApprovalPolicy
import io.mockk.every
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage

class HumanInTheLoopTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    private suspend fun awaitPending(
        store: InMemoryPendingApprovalStore,
        timeoutMs: Long = 2_000
    ): com.arc.reactor.approval.ApprovalSummary {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pending = store.listPending()
            if (pending.isNotEmpty()) return pending[0]
            delay(20)
        }
        throw AssertionError("No pending approval within ${timeoutMs}ms")
    }

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class ApprovalRequired {

        @Test
        fun `should pause and wait for approval before tool execution`() = runBlocking {
            // Arrange: Tool call that requires approval
            val approvalStore = InMemoryPendingApprovalStore(defaultTimeoutMs = 5_000)
            val policy = ToolNameApprovalPolicy(setOf("delete_order"))

            val toolCall = AssistantMessage.ToolCall("tc-1", "call", "delete_order", """{"orderId":"123"}""")
            val toolCallSpec = fixture.mockToolCallResponse(listOf(toolCall))
            val finalSpec = fixture.mockFinalResponse("Order deleted successfully")
            every { fixture.callResponseSpec.chatResponse() } returnsMany listOf(
                toolCallSpec.chatResponse(), finalSpec.chatResponse()
            )

            val tool = AgentTestFixture.toolCallback("delete_order", "Delete an order", "Order deleted")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                toolApprovalPolicy = policy,
                pendingApprovalStore = approvalStore
            )

            // Act: Start execution in background
            val result = async {
                executor.execute(
                    AgentCommand(
                        systemPrompt = "You are an admin assistant.",
                        userPrompt = "Delete order 123"
                    )
                )
            }

            // Wait for approval request (polling instead of fixed delay)
            val approval = awaitPending(approvalStore)
            assertEquals("delete_order", approval.toolName) { "Tool name mismatch" }

            // Approve the tool call
            approvalStore.approve(approval.id)

            // Assert: execution completes successfully
            val agentResult = result.await()
            agentResult.assertSuccess()
        }

        @Test
        fun `should reject tool execution when human rejects`() = runBlocking {
            val approvalStore = InMemoryPendingApprovalStore(defaultTimeoutMs = 5_000)
            val policy = ToolNameApprovalPolicy(setOf("delete_order"))

            val toolCall = AssistantMessage.ToolCall("tc-1", "call", "delete_order", """{"orderId":"123"}""")
            val toolCallSpec = fixture.mockToolCallResponse(listOf(toolCall))
            val finalSpec = fixture.mockFinalResponse("I cannot delete the order because it was rejected.")
            every { fixture.callResponseSpec.chatResponse() } returnsMany listOf(
                toolCallSpec.chatResponse(), finalSpec.chatResponse()
            )

            val tool = AgentTestFixture.toolCallback("delete_order", "Delete an order", "Order deleted")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                toolApprovalPolicy = policy,
                pendingApprovalStore = approvalStore
            )

            val result = async {
                executor.execute(
                    AgentCommand(
                        systemPrompt = "You are an admin assistant.",
                        userPrompt = "Delete order 123"
                    )
                )
            }

            // Wait for approval request (polling instead of fixed delay)
            val approval = awaitPending(approvalStore)

            // Reject the tool call
            approvalStore.reject(approval.id, "Not authorized")

            val agentResult = result.await()
            // The agent should still succeed (LLM gets rejection message and generates final answer)
            agentResult.assertSuccess()
        }
    }

    @Nested
    inner class NoApprovalNeeded {

        @Test
        fun `should execute tool without approval when policy returns false`() = runBlocking {
            val approvalStore = InMemoryPendingApprovalStore()
            val policy = ToolNameApprovalPolicy(setOf("delete_order")) // Only delete needs approval

            val toolCall = AssistantMessage.ToolCall("tc-1", "call", "search_orders", """{"query":"123"}""")
            val toolCallSpec = fixture.mockToolCallResponse(listOf(toolCall))
            val finalSpec = fixture.mockFinalResponse("Found order 123")
            every { fixture.callResponseSpec.chatResponse() } returnsMany listOf(
                toolCallSpec.chatResponse(), finalSpec.chatResponse()
            )

            val tool = AgentTestFixture.toolCallback("search_orders", "Search orders", "Order 123 found")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                toolApprovalPolicy = policy,
                pendingApprovalStore = approvalStore
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are an assistant.",
                    userPrompt = "Search for order 123"
                )
            )

            result.assertSuccess()
            // No pending approvals should exist
            assertTrue(approvalStore.listPending().isEmpty()) {
                "No approvals should be pending for search_orders"
            }
        }

        @Test
        fun `should execute normally when no approval policy configured`() = runBlocking {
            // No policy = no HITL
            val toolCall = AssistantMessage.ToolCall("tc-1", "call", "delete_order", """{"orderId":"123"}""")
            val toolCallSpec = fixture.mockToolCallResponse(listOf(toolCall))
            val finalSpec = fixture.mockFinalResponse("Done")
            every { fixture.callResponseSpec.chatResponse() } returnsMany listOf(
                toolCallSpec.chatResponse(), finalSpec.chatResponse()
            )

            val tool = AgentTestFixture.toolCallback("delete_order", "Delete an order", "Deleted")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
                // No toolApprovalPolicy, no pendingApprovalStore
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Delete order 123"
                )
            )

            result.assertSuccess()
        }
    }

    @Nested
    inner class ApprovalTimeout {

        @Test
        fun `should reject tool call on approval timeout`() = runBlocking {
            val shortTimeoutStore = InMemoryPendingApprovalStore(defaultTimeoutMs = 300)
            val policy = ToolNameApprovalPolicy(setOf("slow_tool"))

            val toolCall = AssistantMessage.ToolCall("tc-1", "call", "slow_tool", """{}""")
            val toolCallSpec = fixture.mockToolCallResponse(listOf(toolCall))
            val finalSpec = fixture.mockFinalResponse("Tool timed out waiting for approval")
            every { fixture.callResponseSpec.chatResponse() } returnsMany listOf(
                toolCallSpec.chatResponse(), finalSpec.chatResponse()
            )

            val tool = AgentTestFixture.toolCallback("slow_tool", "A slow tool", "result")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                toolApprovalPolicy = policy,
                pendingApprovalStore = shortTimeoutStore
            )

            // No one approves → should timeout
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Run slow tool"
                )
            )

            // Agent should still produce a response (LLM handles the timeout message)
            agentResult(result)
        }

        private fun agentResult(result: com.arc.reactor.agent.model.AgentResult) {
            result.assertSuccess()
        }
    }
}
