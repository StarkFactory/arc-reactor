package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.tool.ToolCallback
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 워커 에이전트를 도구로 감싸는 어댑터
 *
 * Supervisor 패턴의 핵심 구현입니다.
 * 다른 에이전트를 ToolCallback으로 감싸서, Supervisor의 도구 목록에 등록합니다.
 * Supervisor의 ReAct 루프가 이 도구를 호출하면 → 워커 에이전트가 실행됩니다.
 *
 * ## 기존 코드 수정 없이 동작하는 원리
 * ```
 * SpringAiAgentExecutor (기존 코드, 수정 없음)
 *   └── 도구 목록에 WorkerAgentTool이 포함됨
 *         └── call() 시 워커 AgentExecutor.execute() 호출
 * ```
 *
 * @param node 워커 에이전트 노드 정의
 * @param agentExecutor 워커 에이전트의 실행기
 */
class WorkerAgentTool(
    private val node: AgentNode,
    private val agentExecutor: AgentExecutor
) : ToolCallback {

    override val name = "delegate_to_${node.name}"

    override val description = "Delegate a task to the '${node.name}' agent. ${node.description}"

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "instruction": {
                  "type": "string",
                  "description": "The task instruction to give to the ${node.name} agent"
                }
              },
              "required": ["instruction"]
            }
        """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val instruction = arguments["instruction"] as? String
            ?: return "Error: 'instruction' parameter is required"

        logger.info { "WorkerAgentTool: delegating to '${node.name}' with instruction length=${instruction.length}" }

        val result = agentExecutor.execute(
            AgentCommand(
                systemPrompt = node.systemPrompt,
                userPrompt = instruction,
                maxToolCalls = node.maxToolCalls
            )
        )

        return if (result.success) {
            result.content ?: "Worker completed but returned no content"
        } else {
            "Worker '${node.name}' failed: ${result.errorMessage}"
        }
    }
}
