package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Sequential execution orchestrator.
 *
 * Executes agents in A -> B -> C order.
 * The output of the previous agent becomes the input for the next agent.
 *
 * ## How It Works
 * ```
 * [Researcher] "Research AI trends"
 *       | (research results)
 * [Writer] "Write a report based on the above material"
 *       | (report draft)
 * [Reviewer] "Review and improve the above report"
 *       |
 *   Final report
 * ```
 *
 * ## Failure Handling
 * If an intermediate node fails, execution stops immediately and a failure result is returned.
 */
class SequentialOrchestrator : MultiAgentOrchestrator {

    override suspend fun execute(
        command: AgentCommand,
        nodes: List<AgentNode>,
        agentFactory: (AgentNode) -> AgentExecutor
    ): MultiAgentResult {
        if (nodes.isEmpty()) {
            return MultiAgentResult(
                success = false,
                finalResult = AgentResult.failure("No agent nodes provided"),
                totalDurationMs = 0
            )
        }

        val startTime = System.currentTimeMillis()
        val nodeResults = mutableListOf<NodeResult>()
        var currentInput = command.userPrompt

        for (node in nodes) {
            logger.info { "Sequential: executing node '${node.name}' with input length=${currentInput.length}" }
            val nodeStart = System.currentTimeMillis()

            val agent = agentFactory(node)
            val nodeCommand = command.copy(
                systemPrompt = node.systemPrompt,
                userPrompt = currentInput
            )

            val result = agent.execute(nodeCommand)
            val nodeDuration = System.currentTimeMillis() - nodeStart

            nodeResults.add(NodeResult(node.name, result, nodeDuration))

            if (!result.success) {
                logger.warn { "Sequential: node '${node.name}' failed: ${result.errorMessage}" }
                return MultiAgentResult(
                    success = false,
                    finalResult = result,
                    nodeResults = nodeResults,
                    totalDurationMs = System.currentTimeMillis() - startTime
                )
            }

            // Pass the previous node's output as input to the next node
            currentInput = result.content ?: ""
            logger.info { "Sequential: node '${node.name}' completed in ${nodeDuration}ms" }
        }

        val finalResult = nodeResults.last().result
        return MultiAgentResult(
            success = true,
            finalResult = finalResult,
            nodeResults = nodeResults,
            totalDurationMs = System.currentTimeMillis() - startTime
        )
    }
}
