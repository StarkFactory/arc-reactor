package com.arc.reactor.approval

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolApprovalPolicyTest {

    @Nested
    inner class AlwaysApprovePolicyTest {

        @Test
        fun `should never require approval`() {
            val policy = AlwaysApprovePolicy()

            assertFalse(policy.requiresApproval("any_tool", emptyMap())) {
                "AlwaysApprovePolicy should never require approval"
            }
            assertFalse(policy.requiresApproval("delete_everything", mapOf("force" to true))) {
                "AlwaysApprovePolicy should never require approval even for destructive tools"
            }
        }
    }

    @Nested
    inner class ToolNameApprovalPolicyTest {

        @Test
        fun `should require approval for configured tool names`() {
            val policy = ToolNameApprovalPolicy(setOf("delete_order", "process_refund"))

            assertTrue(policy.requiresApproval("delete_order", emptyMap())) {
                "Should require approval for delete_order"
            }
            assertTrue(policy.requiresApproval("process_refund", mapOf("amount" to 100))) {
                "Should require approval for process_refund"
            }
        }

        @Test
        fun `should not require approval for unconfigured tool names`() {
            val policy = ToolNameApprovalPolicy(setOf("delete_order"))

            assertFalse(policy.requiresApproval("search_orders", emptyMap())) {
                "Should not require approval for search_orders"
            }
            assertFalse(policy.requiresApproval("track_shipping", emptyMap())) {
                "Should not require approval for track_shipping"
            }
        }

        @Test
        fun `should handle empty tool names set`() {
            val policy = ToolNameApprovalPolicy(emptySet())

            assertFalse(policy.requiresApproval("any_tool", emptyMap())) {
                "Empty tool names set should never require approval"
            }
        }
    }

    @Nested
    inner class CustomPolicyTest {

        @Test
        fun `should support custom approval policy`() {
            // Amount-based policy
            val policy = object : ToolApprovalPolicy {
                override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
                    val amount = (arguments["amount"] as? Number)?.toDouble() ?: return false
                    return amount > 1000
                }
            }

            assertFalse(policy.requiresApproval("process_refund", mapOf("amount" to 500))) {
                "Should not require approval for amount <= 1000"
            }
            assertTrue(policy.requiresApproval("process_refund", mapOf("amount" to 5000))) {
                "Should require approval for amount > 1000"
            }
            assertFalse(policy.requiresApproval("process_refund", emptyMap())) {
                "Should not require approval when no amount provided"
            }
        }
    }
}
