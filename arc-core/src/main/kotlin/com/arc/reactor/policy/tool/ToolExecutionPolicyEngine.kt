package com.arc.reactor.policy.tool

/**
 * Central policy engine for tool execution decisions.
 *
 * Separates policy evaluation from transport/channel code (hooks/controllers),
 * so policy logic is managed in one place.
 */
class ToolExecutionPolicyEngine(
    private val toolPolicyProvider: ToolPolicyProvider
) {

    /**
     * Whether the tool is considered a write tool under current policy.
     */
    fun isWriteTool(toolName: String, arguments: Map<String, Any?> = emptyMap()): Boolean {
        if (isReadOnlyPreview(toolName, arguments)) return false
        val policy = toolPolicyProvider.current()
        if (!policy.enabled) return false
        return toolName in policy.writeToolNames
    }

    /**
     * Decide if a tool call is allowed for a given channel.
     */
    fun evaluate(channel: String?, toolName: String, arguments: Map<String, Any?> = emptyMap()): ToolExecutionDecision {
        val policy = toolPolicyProvider.current()
        if (!policy.enabled || policy.writeToolNames.isEmpty()) return ToolExecutionDecision.Allow

        val normalizedChannel = channel?.trim()?.lowercase()
        if (normalizedChannel.isNullOrBlank()) return ToolExecutionDecision.Allow
        if (normalizedChannel !in policy.denyWriteChannels) return ToolExecutionDecision.Allow
        if (!isWriteTool(toolName, arguments)) return ToolExecutionDecision.Allow
        if (toolName in policy.allowWriteToolNamesInDenyChannels) return ToolExecutionDecision.Allow

        val allowForChannel = policy.allowWriteToolNamesByChannel[normalizedChannel] ?: emptySet()
        if (toolName in allowForChannel) return ToolExecutionDecision.Allow

        return ToolExecutionDecision.Deny(policy.denyWriteMessage)
    }

    private fun isReadOnlyPreview(toolName: String, arguments: Map<String, Any?>): Boolean {
        return when (toolName) {
            "work_action_items_to_jira" -> arguments["dryRun"] == true
            "work_release_readiness_pack" -> {
                val autoExecute = arguments["autoExecuteActionItems"] == true
                val dryRun = arguments["dryRunActionItems"]
                !autoExecute && dryRun != false
            }
            else -> false
        }
    }
}

sealed class ToolExecutionDecision {
    data object Allow : ToolExecutionDecision()
    data class Deny(val reason: String) : ToolExecutionDecision()
}
