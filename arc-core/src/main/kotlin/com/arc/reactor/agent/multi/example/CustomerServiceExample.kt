package com.arc.reactor.agent.multi.example

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.multi.AgentNode
import com.arc.reactor.agent.multi.MultiAgent
import com.arc.reactor.agent.multi.MultiAgentResult
import org.springframework.ai.chat.client.ChatClient

/**
 * 멀티 에이전트 사용 예시 -- 고객 서비스 센터 (Supervisor 패턴)
 *
 * ## 이 예시가 보여주는 것
 * 1. 노드를 어디에 정의하는지
 * 2. agentFactory를 어떻게 만드는지
 * 3. ChatController와 어떻게 연결하는지
 *
 * ## 사용법
 * ```kotlin
 * // Spring @Configuration에서 빈으로 등록
 * @Bean
 * fun customerService(chatClient: ChatClient, properties: AgentProperties): CustomerServiceExample {
 *     return CustomerServiceExample(chatClient, properties)
 * }
 *
 * // 컨트롤러에서 사용
 * @PostMapping("/api/support")
 * suspend fun support(@RequestBody request: ChatRequest): ChatResponse {
 *     val result = customerService.handle(request.message, request.userId)
 *     return ChatResponse(content = result.finalResult.content, success = result.success)
 * }
 * ```
 *
 * @see com.arc.reactor.agent.multi.MultiAgent DSL 빌더
 * @see com.arc.reactor.agent.multi.WorkerAgentTool 에이전트를 도구로 래핑하는 어댑터
 * @see com.arc.reactor.agent.multi.SupervisorOrchestrator Supervisor 오케스트레이터
 */
// @Component  <- 자동 등록하려면 주석 해제
class CustomerServiceExample(
    private val chatClient: ChatClient,
    private val properties: AgentProperties
) {

    /**
     * 고객 요청을 처리한다.
     *
     * Supervisor가 요청을 분석하고 적절한 워커 에이전트에 위임한다.
     */
    suspend fun handle(message: String, userId: String? = null): MultiAgentResult {
        return MultiAgent.supervisor()
            // -- 워커 노드 정의 --
            // 각 node()가 하나의 워커 에이전트를 정의한다.
            // description이 중요: Supervisor의 LLM이 이 설명을 읽고
            // 어떤 워커에 위임할지 결정한다.
            .node("order") {
                systemPrompt = "You are an order specialist. Handle order inquiries, modifications, and cancellations."
                description = "Order lookup, modification, cancellation"
                // tools = listOf(orderLookupTool, orderCancelTool)  <- 여기에 실제 도구 연결
                maxToolCalls = 5
            }
            .node("refund") {
                systemPrompt = "You are a refund specialist. Process refund requests according to company policy."
                description = "Refund requests, refund status checks, refund policy guidance"
                // tools = listOf(refundProcessTool, refundStatusTool)
                maxToolCalls = 5
            }
            .node("shipping") {
                systemPrompt = "You are a shipping specialist. Track packages and handle delivery issues."
                description = "Shipment tracking, address changes, delivery delay inquiries"
                // tools = listOf(trackingTool, addressChangeTool)
                maxToolCalls = 5
            }
            // -- 실행 --
            // agentFactory: 각 노드에서 실제 AgentExecutor를 생성하는 함수
            // 이 함수는 워커마다 한 번, Supervisor에 한 번 호출된다.
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
     * AgentNode에서 SpringAiAgentExecutor를 생성한다.
     *
     * 모든 에이전트(Supervisor + 워커)가 이 팩토리를 통해 생성된다.
     * 공통 설정(chatClient, properties)은 공유되고,
     * 노드별 설정(systemPrompt, tools, maxToolCalls)은 각각 다르다.
     */
    private fun createAgent(node: AgentNode): AgentExecutor {
        return SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            // node.tools: 이 노드에 정의된 도구
            // Supervisor 노드의 경우 WorkerAgentTool이 자동으로 포함됨
            toolCallbacks = node.tools,
            localTools = node.localTools
        )
    }
}
