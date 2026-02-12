package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Parallel execution orchestrator.
 *
 * Executes all agents concurrently and merges the results.
 *
 * ## How It Works
 * ```
 *            "Review this PR"
 *           +------+------+
 *           |      |      |
 *     [Security] [Style] [Logic]   <- 3 concurrent executions
 *           |      |      |
 *           +------+------+
 *         Merge results via ResultMerger
 *                  |
 *         Combined review result
 * ```
 *
 * ## Result Merging
 * Merges results from multiple nodes into one via [ResultMerger].
 * Defaults to joining with newlines.
 *
 * @param merger Result merge strategy (default: join with newlines)
 * @param failFast If true, any failure causes overall failure; if false, only successful results are merged
 */
class ParallelOrchestrator(
    private val merger: ResultMerger = ResultMerger.JOIN_WITH_NEWLINE,
    private val failFast: Boolean = false
) : MultiAgentOrchestrator {

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

        logger.info { "Parallel: executing ${nodes.size} nodes concurrently" }

        val nodeResults = coroutineScope {
            nodes.map { node ->
                async {
                    val nodeStart = System.currentTimeMillis()
                    logger.info { "Parallel: starting node '${node.name}'" }

                    val agent = agentFactory(node)
                    val nodeCommand = command.copy(
                        systemPrompt = node.systemPrompt,
                        userPrompt = command.userPrompt
                    )

                    val result = try {
                        agent.execute(nodeCommand)
                    } catch (e: Exception) {
                        logger.error(e) { "Parallel: node '${node.name}' threw exception" }
                        AgentResult.failure("Node '${node.name}' failed: ${e.message}")
                    }

                    val nodeDuration = System.currentTimeMillis() - nodeStart
                    logger.info {
                        "Parallel: node '${node.name}' completed in ${nodeDuration}ms, " +
                            "success=${result.success}"
                    }
                    NodeResult(node.name, result, nodeDuration)
                }
            }.awaitAll()
        }

        val allSuccess = nodeResults.all { it.result.success }
        val hasAnySuccess = nodeResults.any { it.result.success }

        val success = if (failFast) allSuccess else hasAnySuccess
        val mergedContent = merger.merge(nodeResults.filter { it.result.success })
        val allToolsUsed = nodeResults.flatMap { it.result.toolsUsed }

        return MultiAgentResult(
            success = success,
            finalResult = AgentResult(
                success = success,
                content = mergedContent,
                toolsUsed = allToolsUsed,
                durationMs = System.currentTimeMillis() - startTime
            ),
            nodeResults = nodeResults,
            totalDurationMs = System.currentTimeMillis() - startTime
        )
    }
}
