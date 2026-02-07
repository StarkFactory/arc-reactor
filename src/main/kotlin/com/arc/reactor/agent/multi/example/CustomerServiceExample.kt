package com.arc.reactor.agent.multi.example

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.multi.AgentNode
import com.arc.reactor.agent.multi.MultiAgent
import com.arc.reactor.agent.multi.MultiAgentResult
import com.arc.reactor.tool.ToolCallback
import org.springframework.ai.chat.client.ChatClient

/**
 * 멀티에이전트 사용 예시 — 고객 상담 센터 (Supervisor 패턴)
 *
 * ## 이 예시가 보여주는 것
 * 1. node를 어디서 정의하는지
 * 2. agentFactory를 어떻게 만드는지
 * 3. ChatController와 어떻게 연결하는지
 *
 * ## 사용법
 * ```kotlin
 * // Spring @Configuration에서 빈 등록
 * @Bean
 * fun customerService(chatClient: ChatClient, properties: AgentProperties): CustomerServiceExample {
 *     return CustomerServiceExample(chatClient, properties)
 * }
 *
 * // Controller에서 사용
 * @PostMapping("/api/support")
 * suspend fun support(@RequestBody request: ChatRequest): ChatResponse {
 *     val result = customerService.handle(request.message, request.userId)
 *     return ChatResponse(content = result.finalResult.content, success = result.success)
 * }
 * ```
 *
 * @see com.arc.reactor.agent.multi.MultiAgent DSL 빌더
 * @see com.arc.reactor.agent.multi.WorkerAgentTool 에이전트를 도구로 감싸는 어댑터
 * @see com.arc.reactor.agent.multi.SupervisorOrchestrator Supervisor 오케스트레이터
 */
// @Component  ← 주석 해제하면 자동 등록
class CustomerServiceExample(
    private val chatClient: ChatClient,
    private val properties: AgentProperties
) {

    /**
     * 고객 요청을 처리합니다.
     *
     * Supervisor가 요청을 분석하고, 적절한 워커 에이전트에게 위임합니다.
     */
    suspend fun handle(message: String, userId: String? = null): MultiAgentResult {
        return MultiAgent.supervisor()
            // ── 워커 노드 정의 ──
            // 각 node()가 하나의 워커 에이전트를 정의합니다.
            // description이 중요: Supervisor의 LLM이 이 설명을 보고 어떤 워커에 위임할지 판단합니다.
            .node("order") {
                systemPrompt = "You are an order specialist. Handle order inquiries, modifications, and cancellations."
                description = "주문 조회, 변경, 취소"
                // tools = listOf(orderLookupTool, orderCancelTool)  ← 실제 도구 연결
                maxToolCalls = 5
            }
            .node("refund") {
                systemPrompt = "You are a refund specialist. Process refund requests according to company policy."
                description = "환불 신청, 환불 상태 확인, 환불 정책 안내"
                // tools = listOf(refundProcessTool, refundStatusTool)
                maxToolCalls = 5
            }
            .node("shipping") {
                systemPrompt = "You are a shipping specialist. Track packages and handle delivery issues."
                description = "배송 추적, 배송지 변경, 배송 지연 문의"
                // tools = listOf(trackingTool, addressChangeTool)
                maxToolCalls = 5
            }
            // ── 실행 ──
            // agentFactory: 각 node를 실제 AgentExecutor로 만드는 함수
            // 이 함수가 워커마다 한 번, Supervisor에 한 번 호출됩니다.
            .execute(
                command = AgentCommand(
                    systemPrompt = "You are a customer service supervisor.",
                    userPrompt = message,
                    userId = userId
                ),
                agentFactory = { node -> createAgent(node) }
            )
    }

    /**
     * AgentNode로부터 SpringAiAgentExecutor를 생성합니다.
     *
     * 모든 에이전트(Supervisor + Worker)가 이 팩토리를 통해 생성됩니다.
     * 공통 설정(chatClient, properties)은 공유하고,
     * 노드별 설정(systemPrompt, tools, maxToolCalls)은 각자 다릅니다.
     */
    private fun createAgent(node: AgentNode): AgentExecutor {
        return SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            // node.tools: 이 노드에 정의된 도구들
            // Supervisor 노드의 경우 WorkerAgentTool들이 자동으로 들어옵니다
            toolCallbacks = node.tools,
            localTools = node.localTools
        )
    }
}
