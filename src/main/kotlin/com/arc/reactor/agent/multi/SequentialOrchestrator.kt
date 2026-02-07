package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 순차 실행 오케스트레이터 (Sequential)
 *
 * 에이전트를 A → B → C 순서로 실행합니다.
 * 이전 에이전트의 출력이 다음 에이전트의 입력이 됩니다.
 *
 * ## 동작 방식
 * ```
 * [Researcher] "AI 트렌드 조사해줘"
 *       ↓ (조사 결과)
 * [Writer] "위 자료를 바탕으로 리포트를 작성해줘"
 *       ↓ (리포트 초안)
 * [Reviewer] "위 리포트를 검토하고 개선해줘"
 *       ↓
 *   최종 리포트
 * ```
 *
 * ## 실패 처리
 * 중간 노드가 실패하면 즉시 중단하고 실패 결과를 반환합니다.
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
