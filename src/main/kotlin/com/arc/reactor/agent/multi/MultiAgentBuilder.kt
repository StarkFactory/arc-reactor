package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback

/**
 * 멀티에이전트 빌더
 *
 * 간결한 DSL로 멀티에이전트 파이프라인을 구성합니다.
 *
 * ## Sequential 예시
 * ```kotlin
 * val result = MultiAgent.sequential()
 *     .node("researcher") {
 *         systemPrompt = "자료를 조사하라"
 *         description = "리서치 전문 에이전트"
 *     }
 *     .node("writer") {
 *         systemPrompt = "리포트를 작성하라"
 *     }
 *     .execute(command, agentFactory)
 * ```
 *
 * ## Parallel 예시
 * ```kotlin
 * val result = MultiAgent.parallel()
 *     .node("security") { systemPrompt = "보안 취약점을 분석하라" }
 *     .node("style") { systemPrompt = "코드 스타일을 검사하라" }
 *     .node("logic") { systemPrompt = "비즈니스 로직을 검증하라" }
 *     .execute(command, agentFactory)
 * ```
 *
 * ## Supervisor 예시
 * ```kotlin
 * val result = MultiAgent.supervisor()
 *     .node("order") {
 *         systemPrompt = "주문 관련 업무를 처리하라"
 *         description = "주문 조회, 변경, 취소"
 *     }
 *     .node("refund") {
 *         systemPrompt = "환불 업무를 처리하라"
 *         description = "환불 신청, 상태 확인"
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

        internal fun build() = AgentNode(
            name = name,
            systemPrompt = systemPrompt,
            description = description,
            tools = tools,
            localTools = localTools,
            maxToolCalls = maxToolCalls
        )
    }

    internal enum class OrchestratorType {
        SEQUENTIAL, PARALLEL, SUPERVISOR
    }
}
