package com.arc.reactor.approval

/**
 * Tool Approval Policy
 *
 * Determines whether a tool call requires human approval before execution.
 * Implement this interface to define custom approval rules.
 *
 * ## Example: Approve all destructive operations
 * ```kotlin
 * @Component
 * class DestructiveToolPolicy : ToolApprovalPolicy {
 *     private val destructiveTools = setOf("delete_order", "process_refund", "cancel_subscription")
 *
 *     override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
 *         return toolName in destructiveTools
 *     }
 * }
 * ```
 *
 * ## Example: Amount threshold
 * ```kotlin
 * class AmountPolicy : ToolApprovalPolicy {
 *     override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
 *         val amount = (arguments["amount"] as? Number)?.toDouble() ?: return false
 *         return amount > 10_000
 *     }
 * }
 * ```
 *
 * @see AlwaysApprovePolicy for auto-approve (disables HITL)
 * @see ToolNameApprovalPolicy for simple name-based filtering
 */
interface ToolApprovalPolicy {

    /**
     * Check if a tool call requires human approval.
     *
     * @param toolName Name of the tool being called
     * @param arguments Tool call arguments from the LLM
     * @return true if approval is required before execution
     */
    fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean
}

/**
 * Always auto-approves all tool calls (HITL disabled).
 * This is the default policy.
 */
class AlwaysApprovePolicy : ToolApprovalPolicy {
    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean = false
}

/**
 * Name-based approval policy.
 * Tool calls whose names are in the given set require human approval.
 *
 * ```yaml
 * arc:
 *   reactor:
 *     approval:
 *       enabled: true
 *       tool-names:
 *         - delete_order
 *         - process_refund
 * ```
 */
class ToolNameApprovalPolicy(
    private val toolNames: Set<String>
) : ToolApprovalPolicy {
    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
        return toolName in toolNames
    }
}
