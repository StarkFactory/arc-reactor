package com.arc.reactor.hook.impl

import com.arc.reactor.policy.tool.ToolExecutionDecision
import com.arc.reactor.policy.tool.ToolExecutionPolicyEngine
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
    private val toolExecutionPolicyEngine: ToolExecutionPolicyEngine
) : BeforeToolCallHook {

    override val order: Int = 10
    override val failOnError: Boolean = true

    override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
        return when (
            val decision = toolExecutionPolicyEngine.evaluate(
                channel = context.agentContext.channel,
                toolName = context.toolName
            )
        ) {
            is ToolExecutionDecision.Allow -> HookResult.Continue
            is ToolExecutionDecision.Deny -> {
                logger.info {
                    "Write tool blocked by policy: tool=${context.toolName} " +
                        "channel=${context.agentContext.channel} userId=${context.agentContext.userId}"
                }
                HookResult.Reject(decision.reason)
            }
        }
    }
}
