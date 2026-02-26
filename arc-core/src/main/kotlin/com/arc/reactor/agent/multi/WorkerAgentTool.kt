package com.arc.reactor.agent.multi

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.tool.ToolCallback
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Adapter that wraps a worker agent as a tool.
 *
 * This is the core implementation of the Supervisor pattern.
 * Wraps another agent as a ToolCallback and registers it in the Supervisor's tool list.
 * When the Supervisor's ReAct loop calls this tool, the worker agent is executed.
 *
 * ## How It Works Without Modifying Existing Code
 * ```
 * SpringAiAgentExecutor (existing code, no modifications)
 *   +-- WorkerAgentTool is included in the tool list
 *         +-- On call(), invokes worker AgentExecutor.execute()
 * ```
 *
 * ## Metadata Propagation
 * When [parentCommand] is provided, the worker agent inherits the parent's
 * [metadata][AgentCommand.metadata] and [userId][AgentCommand.userId].
 * This ensures tenant-scoped metrics (quota, cost, HITL) are correctly attributed
 * to the originating tenant even in multi-agent orchestration.
 *
 * @param node Worker agent node definition
 * @param agentExecutor Executor for the worker agent
 * @param parentCommand Parent agent command for metadata propagation (optional for backward compatibility)
 */
class WorkerAgentTool(
    private val node: AgentNode,
    private val agentExecutor: AgentExecutor,
    private val parentCommand: AgentCommand? = null
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
                maxToolCalls = node.maxToolCalls,
                userId = parentCommand?.userId,
                metadata = parentCommand?.metadata ?: emptyMap()
            )
        )

        return if (result.success) {
            result.content ?: "Worker completed but returned no content"
        } else {
            "Worker '${node.name}' failed: ${result.errorMessage}"
        }
    }
}
