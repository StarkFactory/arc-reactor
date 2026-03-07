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
}
