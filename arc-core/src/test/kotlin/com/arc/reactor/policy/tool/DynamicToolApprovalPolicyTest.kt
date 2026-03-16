package com.arc.reactor.policy.tool

import com.arc.reactor.agent.config.ToolPolicyProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DynamicToolApprovalPolicy에 대한 테스트.
 *
 * 동적 도구 승인 정책의 동작을 검증합니다.
 */
class DynamicToolApprovalPolicyTest {

    @Test
    fun `require approval for preview release readiness pack하지 않는다`() {
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
    fun `requires은(는) approval for auto execute release readiness pack`() {
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
