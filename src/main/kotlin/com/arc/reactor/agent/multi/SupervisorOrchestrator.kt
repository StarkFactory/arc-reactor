package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Supervisor 오케스트레이터
 *
 * 매니저 에이전트가 워커 에이전트들에게 작업을 위임합니다.
 *
 * ## 핵심 설계 원리
 * 기존 SpringAiAgentExecutor를 전혀 수정하지 않습니다!
 * 각 워커 에이전트를 [WorkerAgentTool]로 감싸서 Supervisor의 도구 목록에 등록합니다.
 * Supervisor의 ReAct 루프가 자연스럽게 워커를 "도구처럼" 호출합니다.
 *
 * ## 동작 방식
 * ```
 * 사용자: "주문 환불해주세요"
 *          ↓
 * [Supervisor Agent]
 *   시스템 프롬프트: "적절한 전문 에이전트에게 작업을 위임하라"
 *   도구 목록:
 *     - delegate_to_order_agent     ← WorkerAgentTool
 *     - delegate_to_refund_agent    ← WorkerAgentTool
 *     - delegate_to_shipping_agent  ← WorkerAgentTool
 *          ↓ (Supervisor가 refund_agent 도구를 호출)
 * [Refund Agent]
 *   시스템 프롬프트: "환불 정책에 따라 처리하라"
 *   도구: checkOrder, processRefund
 *          ↓ (환불 결과)
 * [Supervisor Agent]
 *   → 결과를 받아 최종 응답 생성
 * ```
 *
 * @param supervisorSystemPrompt Supervisor 에이전트의 시스템 프롬프트 (커스텀 가능)
 */
class SupervisorOrchestrator(
    private val supervisorSystemPrompt: String? = null
) : MultiAgentOrchestrator {

    override suspend fun execute(
        command: AgentCommand,
        nodes: List<AgentNode>,
        agentFactory: (AgentNode) -> AgentExecutor
    ): MultiAgentResult {
        if (nodes.isEmpty()) {
            return MultiAgentResult(
                success = false,
                finalResult = AgentResult.failure("No worker agent nodes provided"),
                totalDurationMs = 0
            )
        }

        val startTime = System.currentTimeMillis()

        // 1. 각 워커 노드를 WorkerAgentTool로 변환
        val workerTools = nodes.map { node ->
            val workerAgent = agentFactory(node)
            WorkerAgentTool(node, workerAgent)
        }

        logger.info { "Supervisor: created ${workerTools.size} worker tools: ${workerTools.map { it.name }}" }

        // 2. Supervisor용 시스템 프롬프트 생성
        val systemPrompt = supervisorSystemPrompt ?: buildDefaultSupervisorPrompt(nodes)

        // 3. Supervisor 에이전트 노드 생성 (워커 도구들을 장착)
        val supervisorNode = AgentNode(
            name = "supervisor",
            systemPrompt = systemPrompt,
            tools = workerTools,
            maxToolCalls = nodes.size * 2 // 워커 수의 2배 (재시도 여유)
        )

        // 4. Supervisor 에이전트 실행 (기존 SpringAiAgentExecutor 그대로 사용)
        val supervisorAgent = agentFactory(supervisorNode)
        val result = supervisorAgent.execute(
            command.copy(systemPrompt = systemPrompt)
        )

        val totalDuration = System.currentTimeMillis() - startTime
        logger.info { "Supervisor: completed in ${totalDuration}ms, success=${result.success}" }

        return MultiAgentResult(
            success = result.success,
            finalResult = result,
            nodeResults = listOf(NodeResult("supervisor", result, totalDuration)),
            totalDurationMs = totalDuration
        )
    }

    private fun buildDefaultSupervisorPrompt(nodes: List<AgentNode>): String {
        val workerDescriptions = nodes.joinToString("\n") { node ->
            "- delegate_to_${node.name}: ${node.description.ifEmpty { node.systemPrompt.take(100) }}"
        }

        return """
            You are a supervisor agent that delegates tasks to specialized worker agents.

            Available workers:
            $workerDescriptions

            Instructions:
            1. Analyze the user's request
            2. Decide which worker agent(s) to delegate to
            3. Call the appropriate delegate_to_* tool with clear instructions
            4. Synthesize the worker's response into a final answer for the user

            Always delegate to the most appropriate worker. Do not try to answer directly.
        """.trimIndent()
    }
}
