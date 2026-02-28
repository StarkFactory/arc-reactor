package com.arc.reactor.scheduler

import java.time.Instant

/**
 * Execution mode for a scheduled job.
 *
 * - MCP_TOOL: Directly invokes a single MCP tool (original behavior).
 * - AGENT: Runs the full ReAct agent loop — LLM reasons over multiple MCP tools and returns a natural-language result.
 */
enum class ScheduledJobType {
    MCP_TOOL, AGENT
}

/**
 * Scheduled job definition supporting both MCP tool execution and full agent execution.
 *
 * ## MCP_TOOL mode (original)
 * Requires [mcpServerName] and [toolName]. Directly invokes a single tool.
 *
 * ## AGENT mode (new)
 * Requires [agentPrompt]. Runs the full ReAct loop with all registered MCP tools.
 * System prompt is resolved in order: [agentSystemPrompt] → [personaId] → default persona → fallback.
 */
data class ScheduledJob(
    val id: String = "",
    val name: String,
    val description: String? = null,
    val cronExpression: String,
    val timezone: String = "Asia/Seoul",
    val jobType: ScheduledJobType = ScheduledJobType.MCP_TOOL,

    // MCP_TOOL mode fields
    val mcpServerName: String? = null,
    val toolName: String? = null,
    val toolArguments: Map<String, Any> = emptyMap(),

    // AGENT mode fields
    val agentPrompt: String? = null,
    val personaId: String? = null,
    val agentSystemPrompt: String? = null,
    val agentModel: String? = null,
    val agentMaxToolCalls: Int? = null,

    // Common
    val slackChannelId: String? = null,
    val enabled: Boolean = true,
    val lastRunAt: Instant? = null,
    val lastStatus: JobExecutionStatus? = null,
    val lastResult: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

enum class JobExecutionStatus {
    SUCCESS, FAILED, RUNNING, SKIPPED
}
