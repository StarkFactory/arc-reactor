package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand

/**
 * 멀티 에이전트 오케스트레이터 인터페이스.
 *
 * 여러 에이전트를 결합하여 단일 작업을 수행한다.
 * 세 가지 패턴을 지원: Sequential, Parallel, Supervisor.
 *
 * ## 사용 예시
 * ```kotlin
 * val orchestrator = SequentialOrchestrator()
 * val result = orchestrator.execute(
 *     command = AgentCommand(systemPrompt = "", userPrompt = "Write a report"),
 *     nodes = listOf(researcherNode, writerNode, reviewerNode),
 *     agentFactory = { node -> createAgent(node) }
 * )
 * ```
 *
 * @see SequentialOrchestrator A -> B -> C 순차 실행
 * @see ParallelOrchestrator A, B, C 동시 실행
 * @see SupervisorOrchestrator 관리자가 워커에 위임
 */
interface MultiAgentOrchestrator {

    /**
     * 멀티 에이전트 오케스트레이션을 실행한다.
     *
     * @param command 원본 사용자 요청
     * @param nodes 실행할 에이전트 노드 목록
     * @param agentFactory 노드에서 AgentExecutor를 생성하는 팩토리
     * @return 전체 실행 결과
     */
    suspend fun execute(
        command: AgentCommand,
        nodes: List<AgentNode>,
        agentFactory: (AgentNode) -> AgentExecutor
    ): MultiAgentResult
}
