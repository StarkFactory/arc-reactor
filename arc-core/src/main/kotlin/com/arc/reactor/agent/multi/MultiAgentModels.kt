package com.arc.reactor.agent.multi

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback

/**
 * Multi-agent node definition.
 *
 * Defines a single agent role. Each node has its own system prompt and tools.
 *
 * @param name Node name (e.g., "researcher", "writer", "reviewer")
 * @param systemPrompt System prompt that defines this agent's role
 * @param description What this agent does (referenced for routing in the Supervisor pattern)
 * @param tools List of ToolCallback-based tools
 * @param localTools List of LocalTool-based tools
 * @param maxToolCalls Maximum number of tool calls for this agent
 */
data class AgentNode(
    val name: String,
    val systemPrompt: String,
    val description: String = "",
    val tools: List<ToolCallback> = emptyList(),
    val localTools: List<LocalTool> = emptyList(),
    val maxToolCalls: Int = 10
)

/**
 * Multi-agent execution result.
 *
 * @param success Whether the overall execution succeeded
 * @param finalResult Final result (last node output or merged result)
 * @param nodeResults Execution results for each node (order preserved)
 * @param totalDurationMs Total execution time
 * @param failedNodes Details about which nodes failed and why (empty if all succeeded)
 */
data class MultiAgentResult(
    val success: Boolean,
    val finalResult: AgentResult,
    val nodeResults: List<NodeResult> = emptyList(),
    val totalDurationMs: Long = 0,
    val failedNodes: List<FailedNodeInfo> = emptyList()
) {
    /** Total tokens used across all nodes */
    val totalTokensUsed: Int
        get() = nodeResults.sumOf { it.tokensUsed }
}

/**
 * Individual node execution result.
 */
data class NodeResult(
    val nodeName: String,
    val result: AgentResult,
    val durationMs: Long = 0,
    val tokensUsed: Int = 0
)

/**
 * Information about a failed node for structured error reporting.
 *
 * @param nodeName Name of the failed node
 * @param index Index of the failed node in the execution list
 * @param errorCode Error code from the agent result, if available
 * @param errorMessage Error message describing the failure
 */
data class FailedNodeInfo(
    val nodeName: String,
    val index: Int,
    val errorCode: AgentErrorCode? = null,
    val errorMessage: String? = null
)

/**
 * Merge strategy for parallel execution results.
 */
fun interface ResultMerger {
    /**
     * Merges results from multiple nodes into one.
     *
     * @param results Execution results from each node
     * @return Merged final content
     */
    fun merge(results: List<NodeResult>): String

    companion object {
        /** Join with newline separator */
        val JOIN_WITH_NEWLINE = ResultMerger { results ->
            results.joinToString("\n\n") { "[${it.nodeName}]\n${it.result.content ?: ""}" }
        }

        /** Use only the last successful result */
        val LAST_SUCCESS = ResultMerger { results ->
            results.lastOrNull { it.result.success }?.result?.content ?: "No successful results"
        }
    }
}
