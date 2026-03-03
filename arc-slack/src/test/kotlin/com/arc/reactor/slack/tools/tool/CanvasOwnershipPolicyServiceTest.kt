package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.config.CanvasToolsProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanvasOwnershipPolicyServiceTest {

    @Test
    fun `allows only registered canvas when allowlist is enforced`() {
        val service = InMemoryCanvasOwnershipPolicyService(
            CanvasToolsProperties(enabled = true, allowlistEnforced = true, maxOwnedCanvasIds = 10)
        )

        assertFalse(service.canEdit("F123"), "Unregistered canvas must be blocked")

        service.registerOwned("F123")

        assertTrue(service.canEdit("F123"), "Registered canvas must be editable")
    }

    @Test
    fun `allows any canvas when allowlist enforcement is disabled`() {
        val service = InMemoryCanvasOwnershipPolicyService(
            CanvasToolsProperties(enabled = true, allowlistEnforced = false, maxOwnedCanvasIds = 10)
        )

        assertTrue(service.canEdit("F123"), "Allowlist disabled should allow edits")
    }
}
