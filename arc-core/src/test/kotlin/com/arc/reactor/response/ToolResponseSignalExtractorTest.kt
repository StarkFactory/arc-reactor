package com.arc.reactor.response

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ToolResponseSignalExtractorTest {

    @Test
    fun `extract should infer policy denied from tool error payload`() {
        val signal = ToolResponseSignalExtractor.extract(
            "jira_search_issues",
            """{"ok":false,"error":"Access denied: project is not allowed: CAMPAIGN","grounded":false,"sources":[],"blockReason":"policy_denied"}"""
        )

        assertNotNull(signal, "Structured policy denial payloads should produce a tool response signal")
        assertEquals("policy_denied", signal?.blockReason, "Policy denial should be extracted for downstream metadata")
        assertEquals(false, signal?.grounded, "Policy denial should remain ungrounded")
    }

    @Test
    fun `extract should infer read only mutation from tool error message`() {
        val signal = ToolResponseSignalExtractor.extract(
            "jira_add_comment",
            """{"error":"MCP_READONLY_MODE=true: mutating tool is disabled: jira_add_comment"}"""
        )

        assertNotNull(signal, "Read-only mutation errors should still surface a tool response signal")
        assertEquals("read_only_mutation", signal?.blockReason, "Read-only mutation errors should map to block metadata")
    }

    @Test
    fun `extract should infer identity unresolved from requester mapping failure`() {
        val signal = ToolResponseSignalExtractor.extract(
            "work_personal_focus_plan",
            """{"ok":false,"error":"requester identity could not be resolved for work_personal_focus_plan","grounded":false,"sources":[],"blockReason":"identity_unresolved"}"""
        )

        assertNotNull(signal, "Identity resolution failures should produce a tool response signal")
        assertEquals("identity_unresolved", signal?.blockReason, "Identity failures should map to dedicated block metadata")
        assertEquals(false, signal?.grounded, "Identity failures should remain ungrounded")
    }

    @Test
    fun `extract should infer upstream auth failure from tool error message`() {
        val signal = ToolResponseSignalExtractor.extract(
            "confluence_answer_question",
            """{"ok":false,"error":"Authentication failed. Check your API token.","grounded":false,"sources":[]}"""
        )

        assertNotNull(signal, "Upstream auth failures should produce a tool response signal")
        assertEquals("upstream_auth_failed", signal?.blockReason, "Auth failures should map to dedicated upstream metadata")
        assertEquals(false, signal?.grounded, "Auth failures should remain ungrounded")
    }

    @Test
    fun `extract should infer upstream permission failure from issue visibility error`() {
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
}
