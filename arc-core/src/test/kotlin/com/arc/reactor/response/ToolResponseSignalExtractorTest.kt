package com.arc.reactor.response

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * ToolResponseSignalExtractor에 대한 테스트.
 *
 * 도구 응답에서 신호를 추출하는 로직을 검증합니다.
 */
class ToolResponseSignalExtractorTest {

    @Test
    fun `extract은(는) infer policy denied from tool error payload해야 한다`() {
        val signal = ToolResponseSignalExtractor.extract(
            "jira_search_issues",
            """{"ok":false,"error":"Access denied: project is not allowed: CAMPAIGN","grounded":false,"sources":[],"blockReason":"policy_denied"}"""
        )

        assertNotNull(signal, "Structured policy denial payloads should produce a tool response signal")
        assertEquals("policy_denied", signal?.blockReason, "Policy denial should be extracted for downstream metadata")
        assertEquals(false, signal?.grounded, "Policy denial should remain ungrounded")
    }

    @Test
    fun `extract은(는) infer read only mutation from tool error message해야 한다`() {
        val signal = ToolResponseSignalExtractor.extract(
            "jira_add_comment",
            """{"error":"MCP_READONLY_MODE=true: mutating tool is disabled: jira_add_comment"}"""
        )

        assertNotNull(signal, "Read-only mutation errors should still surface a tool response signal")
        assertEquals("read_only_mutation", signal?.blockReason, "Read-only mutation errors should map to block metadata")
    }

    @Test
    fun `extract은(는) infer identity unresolved from requester mapping failure해야 한다`() {
        val signal = ToolResponseSignalExtractor.extract(
            "work_personal_focus_plan",
            """{"ok":false,"error":"requester identity could not be resolved for work_personal_focus_plan","grounded":false,"sources":[],"blockReason":"identity_unresolved"}"""
        )

        assertNotNull(signal, "Identity resolution failures should produce a tool response signal")
        assertEquals("identity_unresolved", signal?.blockReason, "Identity failures should map to dedicated block metadata")
        assertEquals(false, signal?.grounded, "Identity failures should remain ungrounded")
    }

    @Test
    fun `extract은(는) infer upstream auth failure from tool error message해야 한다`() {
        val signal = ToolResponseSignalExtractor.extract(
            "confluence_answer_question",
            """{"ok":false,"error":"Authentication failed. Check your API token.","grounded":false,"sources":[]}"""
        )

        assertNotNull(signal, "Upstream auth failures should produce a tool response signal")
        assertEquals("upstream_auth_failed", signal?.blockReason, "Auth failures should map to dedicated upstream metadata")
        assertEquals(false, signal?.grounded, "Auth failures should remain ungrounded")
    }

    @Test
    fun `extract은(는) infer upstream permission failure from issue visibility error해야 한다`() {
        val signal = ToolResponseSignalExtractor.extract(
            "jira_get_issue",
            """{"ok":false,"error":"Issue does not exist or you do not have permission to see it.","grounded":false,"sources":[]}"""
        )

        assertNotNull(signal, "Issue visibility failures should still produce a tool response signal")
        assertEquals(
            "upstream_permission_denied",
            signal?.blockReason,
            "Permission-limited upstream issue reads should map to environment metadata"
        )
    }

    @Test
    fun `extract은(는) capture slack send_message delivery success해야 한다`() {
        val signal = ToolResponseSignalExtractor.extract(
            "send_message",
            """{"ok":true,"channel":"C123","ts":"1710000.1234"}"""
        )

        assertNotNull(signal, "Successful Slack delivery should produce a tool response signal")
        assertEquals("slack", signal?.deliveryPlatform, "Slack delivery should set the delivery platform")
        assertEquals("message_send", signal?.deliveryMode, "send_message should map to message_send delivery mode")
    }

    @Test
    fun `extract은(는) capture slack thread reply delivery success해야 한다`() {
        val signal = ToolResponseSignalExtractor.extract(
            "reply_to_thread",
            """{"ok":true,"channel":"C123","ts":"1710000.1235"}"""
        )

        assertNotNull(signal, "Successful Slack thread replies should produce a tool response signal")
        assertEquals("slack", signal?.deliveryPlatform, "Slack thread reply should set the delivery platform")
        assertEquals("thread_reply", signal?.deliveryMode, "reply_to_thread should map to thread_reply delivery mode")
    }

    @Test
    fun `extract은(는) not mark failed slack delivery as acknowledged해야 한다`() {
        val signal = ToolResponseSignalExtractor.extract(
            "send_message",
            """{"ok":false,"error":"channel_not_found"}"""
        )

        assertEquals(null, signal, "Failed Slack deliveries should not produce a delivery acknowledgement signal")
    }
}
