package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Supervisor orchestrator.
 *
 * A manager agent delegates tasks to worker agents.
 *
 * ## Core Design Principle
 * Does not modify the existing SpringAiAgentExecutor at all!
 * Each worker agent is wrapped with [WorkerAgentTool] and registered in the Supervisor's tool list.
 * The Supervisor's ReAct loop naturally calls workers "as tools."
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
 * @param supervisorSystemPrompt System prompt for the Supervisor agent (customizable)
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

        // 1. Convert each worker node to a WorkerAgentTool
        val workerTools = nodes.map { node ->
            val workerAgent = agentFactory(node)
            WorkerAgentTool(node, workerAgent)
        }

        logger.info { "Supervisor: created ${workerTools.size} worker tools: ${workerTools.map { it.name }}" }

        // 2. Generate system prompt for the Supervisor
        val systemPrompt = supervisorSystemPrompt ?: buildDefaultSupervisorPrompt(nodes)

        // 3. Create the Supervisor agent node (equipped with worker tools)
        val supervisorNode = AgentNode(
            name = "supervisor",
            systemPrompt = systemPrompt,
            tools = workerTools,
            maxToolCalls = nodes.size * 2 // 2x number of workers (allows for retries)
        )

        // 4. Execute the Supervisor agent (using existing SpringAiAgentExecutor as-is)
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
