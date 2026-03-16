package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 병렬 실행 오케스트레이터.
 *
 * 모든 에이전트를 동시에 실행하고 결과를 병합한다.
 *
 * ## 동작 방식
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
 * ## 결과 병합
 * [ResultMerger]를 통해 여러 노드의 결과를 하나로 병합한다.
 * 기본값: 줄바꿈으로 결합.
 *
 * @param merger 결과 병합 전략 (기본: 줄바꿈으로 결합)
 * @param failFast true이면 하나의 실패가 전체 실패를 일으킴; false이면 성공한 결과만 병합
 *
 * @see MultiAgentOrchestrator 인터페이스 계약
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

        val defaultTimeout = WorkerAgentTool.DEFAULT_WORKER_TIMEOUT_MS

        logger.info { "Parallel: executing ${nodes.size} nodes concurrently" }

        val nodeResults = coroutineScope {
            nodes.mapIndexed { index, node ->
                async {
                    val nodeStart = System.currentTimeMillis()
                    logger.info { "Parallel: starting node '${node.name}' (index=$index)" }

                    val agent = agentFactory(node)
                    val nodeCommand = command.copy(
                        systemPrompt = node.systemPrompt,
                        userPrompt = command.userPrompt
                    )

                    val timeout = node.timeoutMs ?: defaultTimeout
                    val result = try {
                        withTimeout(timeout) {
                            agent.execute(nodeCommand)
                        }
                    } catch (e: TimeoutCancellationException) {
                        logger.warn { "Parallel: node '${node.name}' timed out after ${timeout}ms" }
                        AgentResult.failure("Node '${node.name}' timed out after ${timeout}ms")
                    } catch (e: Exception) {
                        e.throwIfCancellation()
                        logger.error(e) { "Parallel: node '${node.name}' threw exception" }
                        AgentResult.failure("Node '${node.name}' failed: ${e.message}")
                    }

                    val nodeDuration = System.currentTimeMillis() - nodeStart
                    logger.info {
                        "Parallel: node '${node.name}' completed in ${nodeDuration}ms, " +
                            "success=${result.success}"
                    }
                    val tokensUsed = result.tokenUsage?.totalTokens ?: 0
                    NodeResult(node.name, result, nodeDuration, tokensUsed)
                }
            }.awaitAll()
        }

        val allSuccess = nodeResults.all { it.result.success }
        val hasAnySuccess = nodeResults.any { it.result.success }

        val failedNodes = nodeResults
            .mapIndexedNotNull { index, nodeResult ->
                if (!nodeResult.result.success) {
                    logger.error {
                        "Parallel: node '${nodeResult.nodeName}' (index=$index) failed — " +
                            "errorCode=${nodeResult.result.errorCode}, " +
                            "errorMessage=${nodeResult.result.errorMessage}"
                    }
                    FailedNodeInfo(
                        nodeName = nodeResult.nodeName,
                        index = index,
                        errorCode = nodeResult.result.errorCode,
                        errorMessage = nodeResult.result.errorMessage
                    )
                } else {
                    null
                }
            }

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
            totalDurationMs = System.currentTimeMillis() - startTime,
            failedNodes = failedNodes
        )
    }
}
