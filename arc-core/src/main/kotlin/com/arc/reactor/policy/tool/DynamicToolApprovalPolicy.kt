package com.arc.reactor.policy.tool

import com.arc.reactor.approval.ToolApprovalPolicy

/**
 * Approval policy that combines:
 * - static tool names from config
 * - dynamic write-tool names from [ToolPolicyProvider]
 */
class DynamicToolApprovalPolicy(
    private val staticToolNames: Set<String>,
    private val toolPolicyProvider: ToolPolicyProvider
) : ToolApprovalPolicy {

    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
        if (toolName in staticToolNames) return true
        val policy = toolPolicyProvider.current()
        if (!policy.enabled) return false
        return toolName in policy.writeToolNames
    }
}

