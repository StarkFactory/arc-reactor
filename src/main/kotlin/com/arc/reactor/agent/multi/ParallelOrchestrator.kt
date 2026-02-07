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
 * 병렬 실행 오케스트레이터 (Parallel)
 *
 * 모든 에이전트를 동시에 실행하고 결과를 병합합니다.
 *
 * ## 동작 방식
 * ```
 *            "이 PR 리뷰해줘"
 *           ┌──────┼──────┐
 *           ↓      ↓      ↓
 *     [Security] [Style] [Logic]   ← 3개 동시 실행
 *           ↓      ↓      ↓
 *           └──────┼──────┘
 *         ResultMerger로 결과 병합
 *                  ↓
 *            종합 리뷰 결과
 * ```
 *
 * ## 결과 병합
 * [ResultMerger]를 통해 여러 노드의 결과를 하나로 병합합니다.
 * 기본값은 줄바꿈으로 결합합니다.
 *
 * @param merger 결과 병합 전략 (기본: 줄바꿈 결합)
 * @param failFast true면 하나라도 실패 시 전체 실패, false면 성공한 것만 병합
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
