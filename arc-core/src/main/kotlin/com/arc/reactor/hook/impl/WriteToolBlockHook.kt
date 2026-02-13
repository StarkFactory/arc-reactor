package com.arc.reactor.hook.impl

import com.arc.reactor.agent.config.ToolPolicyProperties
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
    private val properties: ToolPolicyProperties
) : BeforeToolCallHook {

    override val order: Int = 10
    override val failOnError: Boolean = true

    override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
        if (!properties.enabled) return HookResult.Continue
        if (properties.writeToolNames.isEmpty()) return HookResult.Continue

        val channel = context.agentContext.channel?.trim()?.lowercase()
        if (channel.isNullOrBlank()) return HookResult.Continue

        if (channel !in properties.denyWriteChannels.map { it.lowercase() }.toSet()) {
            return HookResult.Continue
        }

        val toolName = context.toolName
        if (toolName !in properties.writeToolNames) return HookResult.Continue

        logger.info {
            "Write tool blocked by policy: tool=$toolName channel=$channel userId=${context.agentContext.userId}"
        }
        return HookResult.Reject(properties.denyWriteMessage)
    }
}

