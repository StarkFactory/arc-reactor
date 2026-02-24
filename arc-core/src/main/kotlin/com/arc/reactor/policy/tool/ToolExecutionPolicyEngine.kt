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
    fun isWriteTool(toolName: String): Boolean {
        val policy = toolPolicyProvider.current()
        if (!policy.enabled) return false
        return toolName in policy.writeToolNames
    }

    /**
     * Decide if a tool call is allowed for a given channel.
     */
    fun evaluate(channel: String?, toolName: String): ToolExecutionDecision {
        val policy = toolPolicyProvider.current()
        if (!policy.enabled || policy.writeToolNames.isEmpty()) return ToolExecutionDecision.Allow

        val normalizedChannel = channel?.trim()?.lowercase()
        if (normalizedChannel.isNullOrBlank()) return ToolExecutionDecision.Allow
        if (normalizedChannel !in policy.denyWriteChannels) return ToolExecutionDecision.Allow
        if (toolName !in policy.writeToolNames) return ToolExecutionDecision.Allow
        if (toolName in policy.allowWriteToolNamesInDenyChannels) return ToolExecutionDecision.Allow

        val allowForChannel = policy.allowWriteToolNamesByChannel[normalizedChannel] ?: emptySet()
        if (toolName in allowForChannel) return ToolExecutionDecision.Allow

        return ToolExecutionDecision.Deny(policy.denyWriteMessage)
    }
}

sealed class ToolExecutionDecision {
    data object Allow : ToolExecutionDecision()
    data class Deny(val reason: String) : ToolExecutionDecision()
}
