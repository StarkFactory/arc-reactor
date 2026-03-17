package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.config.CanvasToolsProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [CanvasOwnershipPolicyService]의 단위 테스트.
 *
 * 캔버스 소유권 허용 목록 정책의 등록/차단 로직을 검증한다.
 */
class CanvasOwnershipPolicyServiceTest {

    @Test
    fun `allows only registered canvas when allowlist은(는) enforced이다`() {
        val service = InMemoryCanvasOwnershipPolicyService(
            CanvasToolsProperties(enabled = true, allowlistEnforced = true, maxOwnedCanvasIds = 10)
        )

        assertFalse(service.canEdit("F123"), "Unregistered canvas must be blocked")

        service.registerOwned("F123")

        assertTrue(service.canEdit("F123"), "Registered canvas must be editable")
    }

    @Test
    fun `allows any canvas when allowlist enforcement은(는) disabled이다`() {
        val service = InMemoryCanvasOwnershipPolicyService(
            CanvasToolsProperties(enabled = true, allowlistEnforced = false, maxOwnedCanvasIds = 10)
        )

        assertTrue(service.canEdit("F123"), "Allowlist disabled should allow edits")
    }
}
