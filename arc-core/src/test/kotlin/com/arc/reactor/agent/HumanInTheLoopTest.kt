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

/**
 * Human-in-the-Loop 승인 흐름에 대한 테스트.
 *
 * 도구 실행 전 일시 중지/승인/거부/타임아웃 시나리오를 검증합니다.
 */
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
        fun `도구 실행 전에 일시 중지하고 승인을 기다려야 한다`() = runBlocking {
            // 준비: 승인이 필요한 도구 호출
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

            // 실행: 백그라운드에서 실행 시작
            val result = async {
                executor.execute(
                    AgentCommand(
                        systemPrompt = "You are an admin assistant.",
                        userPrompt = "Delete order 123"
                    )
                )
            }

            // 승인 요청 대기 (고정 지연 대신 폴링)
            val approval = awaitPending(approvalStore)
            assertEquals("delete_order", approval.toolName) { "Tool name mismatch" }

            // 도구 호출을 승인합니다
            approvalStore.approve(approval.id)

            // 검증: 실행이 성공적으로 완료됩니다
            val agentResult = result.await()
            agentResult.assertSuccess()
        }

        @Test
        fun `사람이 거부하면 도구 실행을 거부해야 한다`() = runBlocking {
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

            // 승인 요청 대기 (고정 지연 대신 폴링)
            val approval = awaitPending(approvalStore)

            // 도구 호출을 거부합니다
            approvalStore.reject(approval.id, "Not authorized")

            val agentResult = result.await()
            // 에이전트는 여전히 성공해야 합니다 (LLM이 거부 메시지를 받고 최종 응답을 생성합니다)
            agentResult.assertSuccess()
        }
    }

    @Nested
    inner class NoApprovalNeeded {

        @Test
        fun `정책이 false를 반환하면 승인 없이 도구를 실행해야 한다`() = runBlocking {
            val approvalStore = InMemoryPendingApprovalStore()
            val policy = ToolNameApprovalPolicy(setOf("delete_order"))  // 삭제만 승인이 필요합니다

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
            // 대기 중인 승인이 없어야 합니다
            assertTrue(approvalStore.listPending().isEmpty()) {
                "No approvals should be pending for search_orders"
            }
        }

        @Test
        fun `승인 정책이 설정되지 않으면 정상적으로 실행해야 한다`() = runBlocking {
            // 정책 없음 = HITL 없음
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
                // toolApprovalPolicy 없음, pendingApprovalStore 없음
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
        fun `승인 타임아웃 시 도구 호출을 거부해야 한다`() = runBlocking {
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

            // 아무도 승인하지 않음 → 타임아웃 발생
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Run slow tool"
                )
            )

            // 에이전트는 여전히 응답을 생성해야 합니다 (LLM이 타임아웃 메시지를 처리합니다)
            agentResult(result)
        }

        private fun agentResult(result: com.arc.reactor.agent.model.AgentResult) {
            result.assertSuccess()
        }
    }
}
