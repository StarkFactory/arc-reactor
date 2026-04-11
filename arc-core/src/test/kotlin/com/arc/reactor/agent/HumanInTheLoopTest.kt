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

        /**
         * R336 regression: 사람이 승인 단계에서 파라미터를 수정(modifiedArguments)하여 승인하면,
         * 도구가 **수정된 파라미터**로 실행되어야 한다. 이전 구현은 `ToolCallOrchestrator`가
         * `ToolApprovalResponse.modifiedArguments`를 전혀 참조하지 않아 원본 LLM 인자로 실행하는
         * silent 버그가 있었다. HITL 핵심 UX("사람이 금액/대상 범위 조정하여 승인")가 모델 레이어
         * 까지만 동작하고 실행 레이어에서 무효화되던 상태를 복구한다.
         */
        @Test
        fun `R336 사람이 승인 시 파라미터를 수정하면 수정된 인자로 실행해야 한다`() = runBlocking {
            val approvalStore = InMemoryPendingApprovalStore(defaultTimeoutMs = 5_000)
            val policy = ToolNameApprovalPolicy(setOf("update_order"))

            val toolCall = AssistantMessage.ToolCall(
                "tc-1", "call", "update_order", """{"orderId":"123","amount":1000}"""
            )
            val toolCallSpec = fixture.mockToolCallResponse(listOf(toolCall))
            val finalSpec = fixture.mockFinalResponse("Order updated with approved amount")
            every { fixture.callResponseSpec.chatResponse() } returnsMany listOf(
                toolCallSpec.chatResponse(), finalSpec.chatResponse()
            )

            // TrackingTool로 실제 tool 호출 시 받은 arguments를 capture
            val tool = TrackingTool(name = "update_order", result = "Order updated")

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
                        systemPrompt = "You are an order admin.",
                        userPrompt = "Update order 123 to 1000"
                    )
                )
            }

            // 승인 요청이 올 때까지 대기
            val approval = awaitPending(approvalStore)
            assertEquals("update_order", approval.toolName) {
                "Tool name should be update_order"
            }

            // 사람이 금액을 1000 → 500으로 조정하여 승인
            val modifiedArgs = mapOf<String, Any?>(
                "orderId" to "123",
                "amount" to 500
            )
            val approved = approvalStore.approve(approval.id, modifiedArguments = modifiedArgs)
            assertTrue(approved) { "Approval should succeed" }

            val agentResult = result.await()
            agentResult.assertSuccess()

            // 도구는 **수정된 인자**로 호출되어야 한다 (원본 1000이 아니라 500)
            assertEquals(1, tool.callCount) {
                "Tool should be called exactly once"
            }
            val receivedArgs = tool.capturedArgs.first()
            assertEquals(500, receivedArgs["amount"]) {
                "R336: 사람이 수정한 amount(500)가 도구에 전달되어야 한다. " +
                    "실제 전달된 값: ${receivedArgs["amount"]} (원본 LLM 값 1000이 그대로 가면 silent 버그 재현)"
            }
            assertEquals("123", receivedArgs["orderId"]) {
                "orderId는 수정하지 않았으므로 원본 그대로 유지되어야 한다"
            }
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
