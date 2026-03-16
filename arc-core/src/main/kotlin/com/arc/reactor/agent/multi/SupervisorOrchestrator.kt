package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Supervisor 오케스트레이터.
 *
 * 관리자 에이전트가 워커 에이전트에 작업을 위임한다.
 *
 * ## 핵심 설계 원칙
 * 기존 SpringAiAgentExecutor를 전혀 수정하지 않는다!
 * 각 워커 에이전트를 [WorkerAgentTool]로 래핑하여 Supervisor의 도구 목록에 등록한다.
 * Supervisor의 ReAct 루프가 자연스럽게 워커를 "도구로서" 호출한다.
 *
 * ## How It Works
 * ```
 * User: "Please refund my order"
 *          |
 * [Supervisor Agent]
 *   System prompt: "Delegate tasks to the appropriate specialist agent"
 *   Tool list:
 *     - delegate_to_order_agent     <- WorkerAgentTool
 *     - delegate_to_refund_agent    <- WorkerAgentTool
 *     - delegate_to_shipping_agent  <- WorkerAgentTool
 *          | (Supervisor calls the refund_agent tool)
 * [Refund Agent]
 *   System prompt: "Process according to refund policy"
 *   Tools: checkOrder, processRefund
 *          | (refund result)
 * [Supervisor Agent]
 *   -> Receives the result and generates the final response
 * ```
 *
 * @param supervisorSystemPrompt Supervisor 에이전트의 시스템 프롬프트 (커스터마이징 가능)
 *
 * @see MultiAgentOrchestrator for the interface contract
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

        // 1. 각 워커 노드를 WorkerAgentTool로 변환 (부모 메타데이터 전파)
        val workerTools = nodes.map { node ->
            val workerAgent = agentFactory(node)
            WorkerAgentTool(
                node = node,
                agentExecutor = workerAgent,
                parentCommand = command,
                workerTimeoutMs = node.timeoutMs ?: WorkerAgentTool.DEFAULT_WORKER_TIMEOUT_MS
            )
        }

        logger.info { "Supervisor: created ${workerTools.size} worker tools: ${workerTools.map { it.name }}" }

        // 2. Supervisor용 시스템 프롬프트 생성
        val systemPrompt = supervisorSystemPrompt ?: buildDefaultSupervisorPrompt(nodes)

        // 3. Supervisor 에이전트 노드 생성 (워커 도구 장착)
        val supervisorNode = AgentNode(
            name = "supervisor",
            systemPrompt = systemPrompt,
            tools = workerTools,
            maxToolCalls = nodes.size * 2 // 2x number of workers (allows for retries)
        )

        // 4. Supervisor 에이전트 실행 (기존 SpringAiAgentExecutor를 그대로 사용)
        val supervisorAgent = agentFactory(supervisorNode)
        val result = supervisorAgent.execute(
            command.copy(
                systemPrompt = systemPrompt,
                maxToolCalls = supervisorNode.maxToolCalls
            )
        )

        val totalDuration = System.currentTimeMillis() - startTime
        logger.info { "Supervisor: completed in ${totalDuration}ms, success=${result.success}" }

        return MultiAgentResult(
            success = result.success,
            finalResult = result,
            nodeResults = listOf(
                NodeResult("supervisor", result, totalDuration, result.tokenUsage?.totalTokens ?: 0)
            ),
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
