package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback

/**
 * 멀티 에이전트 빌더.
 *
 * 간결한 DSL을 사용하여 멀티 에이전트 파이프라인을 구성한다.
 *
 * ## 순차 실행 예시
 * ```kotlin
 * val result = MultiAgent.sequential()
 *     .node("researcher") {
 *         systemPrompt = "Research the given topic"
 *         description = "Research specialist agent"
 *     }
 *     .node("writer") {
 *         systemPrompt = "Write a report based on the research"
 *     }
 *     .execute(command, agentFactory)
 * ```
 *
 * ## 병렬 실행 예시
 * ```kotlin
 * val result = MultiAgent.parallel()
 *     .node("security") { systemPrompt = "Analyze security vulnerabilities" }
 *     .node("style") { systemPrompt = "Check code style" }
 *     .node("logic") { systemPrompt = "Verify business logic" }
 *     .execute(command, agentFactory)
 * ```
 *
 * ## Supervisor 예시
 * ```kotlin
 * val result = MultiAgent.supervisor()
 *     .node("order") {
 *         systemPrompt = "Handle order-related tasks"
 *         description = "Order lookup, modification, cancellation"
 *     }
 *     .node("refund") {
 *         systemPrompt = "Handle refund tasks"
 *         description = "Refund requests, status checks"
 *     }
 *     .execute(command, agentFactory)
 * ```
 */
object MultiAgent {

    fun sequential(): Builder = Builder(OrchestratorType.SEQUENTIAL)

    fun parallel(
        merger: ResultMerger = ResultMerger.JOIN_WITH_NEWLINE,
        failFast: Boolean = false
    ): Builder = Builder(OrchestratorType.PARALLEL, merger = merger, failFast = failFast)

    fun supervisor(
        systemPrompt: String? = null
    ): Builder = Builder(OrchestratorType.SUPERVISOR, supervisorPrompt = systemPrompt)

    class Builder internal constructor(
        private val type: OrchestratorType,
        private val merger: ResultMerger = ResultMerger.JOIN_WITH_NEWLINE,
        private val failFast: Boolean = false,
        private val supervisorPrompt: String? = null
    ) {
        private val nodes = mutableListOf<AgentNode>()

        fun node(name: String, configure: NodeBuilder.() -> Unit): Builder {
            val builder = NodeBuilder(name)
            builder.configure()
            nodes.add(builder.build())
            return this
        }

        fun node(agentNode: AgentNode): Builder {
            nodes.add(agentNode)
            return this
        }

        suspend fun execute(
            command: AgentCommand,
            agentFactory: (AgentNode) -> AgentExecutor
        ): MultiAgentResult {
            val orchestrator = when (type) {
                OrchestratorType.SEQUENTIAL -> SequentialOrchestrator()
                OrchestratorType.PARALLEL -> ParallelOrchestrator(merger, failFast)
                OrchestratorType.SUPERVISOR -> SupervisorOrchestrator(supervisorPrompt)
            }
            return orchestrator.execute(command, nodes, agentFactory)
        }
    }

    class NodeBuilder(private val name: String) {
        var systemPrompt: String = ""
        var description: String = ""
        var tools: List<ToolCallback> = emptyList()
        var localTools: List<LocalTool> = emptyList()
        var maxToolCalls: Int = 10
        var timeoutMs: Long? = null

        internal fun build() = AgentNode(
            name = name,
            systemPrompt = systemPrompt,
            description = description,
            tools = tools,
            localTools = localTools,
            maxToolCalls = maxToolCalls,
            timeoutMs = timeoutMs
        )
    }

    internal enum class OrchestratorType {
        SEQUENTIAL, PARALLEL, SUPERVISOR
    }
}
