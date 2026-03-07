package com.arc.reactor.policy.tool

import com.arc.reactor.agent.config.ToolPolicyProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DynamicToolApprovalPolicyTest {

    @Test
    fun `does not require approval for preview release readiness pack`() {
        val props = ToolPolicyProperties(
            enabled = true,
            writeToolNames = setOf("work_release_readiness_pack")
        )
        val provider = ToolPolicyProvider(props, InMemoryToolPolicyStore(ToolPolicy.fromProperties(props)))
        val policy = DynamicToolApprovalPolicy(
            staticToolNames = emptySet(),
            toolExecutionPolicyEngine = ToolExecutionPolicyEngine(provider)
        )

        val requiresApproval = policy.requiresApproval(
            "work_release_readiness_pack",
            mapOf("dryRunActionItems" to true, "autoExecuteActionItems" to false)
        )

        assertFalse(requiresApproval) {
            "Preview-only release readiness pack should not wait on human approval"
        }
    }

    @Test
    fun `requires approval for auto execute release readiness pack`() {
        val props = ToolPolicyProperties(
            enabled = true,
            writeToolNames = setOf("work_release_readiness_pack")
        )
        val provider = ToolPolicyProvider(props, InMemoryToolPolicyStore(ToolPolicy.fromProperties(props)))
        val policy = DynamicToolApprovalPolicy(
            staticToolNames = emptySet(),
            toolExecutionPolicyEngine = ToolExecutionPolicyEngine(provider)
        )

        val requiresApproval = policy.requiresApproval(
            "work_release_readiness_pack",
            mapOf("dryRunActionItems" to true, "autoExecuteActionItems" to true)
        )

        assertTrue(requiresApproval) {
            "Auto-executing release readiness pack should still require human approval"
        }
    }
}
