package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand

/**
 * 멀티에이전트 오케스트레이터 인터페이스
 *
 * 여러 에이전트를 조합하여 하나의 작업을 수행합니다.
 * Sequential, Parallel, Supervisor 세 가지 패턴을 지원합니다.
 *
 * ## 사용 예시
 * ```kotlin
 * val orchestrator = SequentialOrchestrator()
 * val result = orchestrator.execute(
 *     command = AgentCommand(systemPrompt = "", userPrompt = "리포트 작성해줘"),
 *     nodes = listOf(researcherNode, writerNode, reviewerNode),
 *     agentFactory = { node -> createAgent(node) }
 * )
 * ```
 *
 * @see SequentialOrchestrator A → B → C 순차 실행
 * @see ParallelOrchestrator A, B, C 동시 실행
 * @see SupervisorOrchestrator 매니저가 워커에게 위임
 */
interface MultiAgentOrchestrator {

    /**
     * 멀티에이전트를 실행합니다.
     *
     * @param command 원본 사용자 요청
     * @param nodes 실행할 에이전트 노드 목록
     * @param agentFactory 노드로부터 AgentExecutor를 생성하는 팩토리
     * @return 전체 실행 결과
     */
    suspend fun execute(
        command: AgentCommand,
        nodes: List<AgentNode>,
        agentFactory: (AgentNode) -> AgentExecutor
    ): MultiAgentResult
}
