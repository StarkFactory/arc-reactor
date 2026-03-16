package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 워커 에이전트를 도구로 래핑하는 어댑터.
 *
 * Supervisor 패턴의 핵심 구현이다.
 * 다른 에이전트를 ToolCallback으로 래핑하여 Supervisor의 도구 목록에 등록한다.
 * Supervisor의 ReAct 루프가 이 도구를 호출하면 워커 에이전트가 실행된다.
 *
 * ## 기존 코드 수정 없이 동작하는 방식
 * ```
 * SpringAiAgentExecutor (existing code, no modifications)
 *   +-- WorkerAgentTool is included in the tool list
 *         +-- On call(), invokes worker AgentExecutor.execute()
 * ```
 *
 * ## 메타데이터 전파
 * [parentCommand]가 제공되면 워커 에이전트는 부모의
 * [metadata][AgentCommand.metadata]와 [userId][AgentCommand.userId]를 상속한다.
 * 이를 통해 테넌트 범위 메트릭(할당량, 비용, HITL)이
 * 멀티 에이전트 오케스트레이션에서도 원래 테넌트에 올바르게 귀속된다.
 *
 * @param node 워커 에이전트 노드 정의
 * @param agentExecutor 워커 에이전트 실행기
 * @param parentCommand 메타데이터 전파용 부모 에이전트 명령 (하위 호환성을 위해 선택적)
 */
class WorkerAgentTool(
    private val node: AgentNode,
    private val agentExecutor: AgentExecutor,
    private val parentCommand: AgentCommand? = null,
    private val workerTimeoutMs: Long = DEFAULT_WORKER_TIMEOUT_MS
) : ToolCallback {

    override val name = "delegate_to_${node.name}"

    override val description = "Delegate a task to the '${node.name}' agent. ${node.description}"

    override val timeoutMs: Long
        get() = workerTimeoutMs

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

        val result = try {
            agentExecutor.execute(
                AgentCommand(
                    systemPrompt = node.systemPrompt,
                    userPrompt = instruction,
                    maxToolCalls = node.maxToolCalls,
                    userId = parentCommand?.userId,
                    metadata = parentCommand?.metadata ?: emptyMap()
                )
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "WorkerAgentTool: worker '${node.name}' threw unexpectedly" }
            return "Error: Worker '${node.name}' failed unexpectedly"
        }

        return if (result.success) {
            result.content ?: "Worker completed but returned no content"
        } else {
            "Worker '${node.name}' failed: ${result.errorMessage}"
        }
    }

    companion object {
        /** 워커 에이전트 실행의 기본 타임아웃 (요청 타임아웃 기본값과 동일) */
        const val DEFAULT_WORKER_TIMEOUT_MS = 30_000L
    }
}
