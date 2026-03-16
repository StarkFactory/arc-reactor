package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 순차 실행 오케스트레이터.
 *
 * A -> B -> C 순서로 에이전트를 실행한다.
 * 이전 에이전트의 출력이 다음 에이전트의 입력이 된다.
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
 * ## 실패 처리
 * 중간 노드가 실패하면 실행이 즉시 중단되고 실패 결과가 반환된다.
 *
 * @see MultiAgentOrchestrator for the interface contract
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
        val defaultTimeout = WorkerAgentTool.DEFAULT_WORKER_TIMEOUT_MS

        for ((index, node) in nodes.withIndex()) {
            logger.info { "Sequential: executing node '${node.name}' with input length=${currentInput.length}" }
            val nodeStart = System.currentTimeMillis()

            val agent = agentFactory(node)
            val nodeCommand = command.copy(
                systemPrompt = node.systemPrompt,
                userPrompt = currentInput
            )

            val timeout = node.timeoutMs ?: defaultTimeout
            val result = try {
                withTimeout(timeout) {
                    agent.execute(nodeCommand)
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Sequential: node '${node.name}' timed out after ${timeout}ms" }
                AgentResult.failure("Node '${node.name}' timed out after ${timeout}ms")
            }
            val nodeDuration = System.currentTimeMillis() - nodeStart

            val tokensUsed = result.tokenUsage?.totalTokens ?: 0
            nodeResults.add(NodeResult(node.name, result, nodeDuration, tokensUsed))

            if (!result.success) {
                val failedInfo = FailedNodeInfo(
                    nodeName = node.name,
                    index = index,
                    errorCode = result.errorCode,
                    errorMessage = result.errorMessage
                )
                logger.error {
                    "Sequential: node '${node.name}' (index=$index) failed — " +
                        "errorCode=${result.errorCode}, errorMessage=${result.errorMessage}"
                }
                return MultiAgentResult(
                    success = false,
                    finalResult = result,
                    nodeResults = nodeResults,
                    totalDurationMs = System.currentTimeMillis() - startTime,
                    failedNodes = listOf(failedInfo)
                )
            }

            // 이전 노드의 출력을 다음 노드의 입력으로 전달
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
