package com.arc.reactor.policy.tool

import com.arc.reactor.agent.config.ToolPolicyProperties
import java.time.Instant

/**
 * Tool policy settings (admin-managed when dynamic policy is enabled).
 *
 * This is intentionally global (not per-user) to fit enterprise "one agent" deployments.
 */
data class ToolPolicy(
    val enabled: Boolean,
    val writeToolNames: Set<String>,
    val denyWriteChannels: Set<String>,
    val allowWriteToolNamesInDenyChannels: Set<String>,
    val denyWriteMessage: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    companion object {
        fun fromProperties(props: ToolPolicyProperties): ToolPolicy = ToolPolicy(
            enabled = props.enabled,
            writeToolNames = props.writeToolNames,
            denyWriteChannels = props.denyWriteChannels,
            allowWriteToolNamesInDenyChannels = props.allowWriteToolNamesInDenyChannels,
            denyWriteMessage = props.denyWriteMessage
        )
    }
}
