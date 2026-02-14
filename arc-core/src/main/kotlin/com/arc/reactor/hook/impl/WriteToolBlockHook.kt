package com.arc.reactor.hook.impl

import com.arc.reactor.policy.tool.ToolPolicyProvider
import com.arc.reactor.hook.BeforeToolCallHook
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Blocks side-effecting ("write") tools on specific channels (e.g., Slack).
 *
 * This is a defense-in-depth safety layer:
 * - It prevents accidental destructive operations from chat-first channels.
 * - It complements HITL approval (write tools can still be approved on web).
 *
 * Channel is sourced from [com.arc.reactor.hook.model.HookContext.channel].
 */
class WriteToolBlockHook(
    private val toolPolicyProvider: ToolPolicyProvider
) : BeforeToolCallHook {

    override val order: Int = 10
    override val failOnError: Boolean = true

    override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
        val policy = toolPolicyProvider.current()
        if (!policy.enabled) return HookResult.Continue
        if (policy.writeToolNames.isEmpty()) return HookResult.Continue

        val channel = context.agentContext.channel?.trim()?.lowercase()
        if (channel.isNullOrBlank()) return HookResult.Continue

        if (channel !in policy.denyWriteChannels) return HookResult.Continue

        val toolName = context.toolName
        if (toolName !in policy.writeToolNames) return HookResult.Continue

        logger.info {
            "Write tool blocked by policy: tool=$toolName channel=$channel userId=${context.agentContext.userId}"
        }
        return HookResult.Reject(policy.denyWriteMessage)
    }
}
