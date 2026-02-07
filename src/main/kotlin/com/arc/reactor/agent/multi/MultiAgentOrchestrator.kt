package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand

/**
 * Multi-agent orchestrator interface.
 *
 * Combines multiple agents to perform a single task.
 * Supports three patterns: Sequential, Parallel, and Supervisor.
 *
 * ## Usage Example
 * ```kotlin
 * val orchestrator = SequentialOrchestrator()
 * val result = orchestrator.execute(
 *     command = AgentCommand(systemPrompt = "", userPrompt = "리포트 작성해줘"),
 *     nodes = listOf(researcherNode, writerNode, reviewerNode),
 *     agentFactory = { node -> createAgent(node) }
 * )
 * ```
 *
 * @see SequentialOrchestrator A -> B -> C sequential execution
 * @see ParallelOrchestrator A, B, C concurrent execution
 * @see SupervisorOrchestrator Manager delegates to workers
 */
interface MultiAgentOrchestrator {

    /**
     * Executes multi-agent orchestration.
     *
     * @param command Original user request
     * @param nodes List of agent nodes to execute
     * @param agentFactory Factory that creates an AgentExecutor from a node
     * @return Overall execution result
     */
    suspend fun execute(
        command: AgentCommand,
        nodes: List<AgentNode>,
        agentFactory: (AgentNode) -> AgentExecutor
    ): MultiAgentResult
}
