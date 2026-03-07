package com.arc.reactor.mcp

import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class McpToolCallbackTest {

    @Test
    fun `returns structured content when text output does not carry machine-readable payload`() = runBlocking {
        val client = mockk<McpSyncClient>()
        val callback = McpToolCallback(
            client = client,
            name = "jira_get_issue",
            description = "Get Jira issue",
            mcpInputSchema = null
        )
        val result = McpSchema.CallToolResult(
            listOf(McpSchema.TextContent("Issue DEV-51 summary")),
            false,
            mapOf(
                "ok" to true,
                "grounded" to true,
                "sources" to listOf(mapOf("title" to "DEV-51", "url" to "https://example.test/browse/DEV-51"))
            ),
            emptyMap()
        )
        every { client.callTool(any()) } returns result

        val output = callback.call(mapOf("issueKey" to "DEV-51"))

        assertEquals(
            """{"ok":true,"grounded":true,"sources":[{"title":"DEV-51","url":"https://example.test/browse/DEV-51"}]}""",
            output,
            "Structured MCP content should be preferred when it carries grounded/source metadata"
        )
    }

    @Test
    fun `keeps textual json output when it already contains machine-readable payload`() = runBlocking {
        val client = mockk<McpSyncClient>()
        val callback = McpToolCallback(
            client = client,
            name = "jira_get_issue",
            description = "Get Jira issue",
            mcpInputSchema = null
        )
        val jsonText = """{"ok":true,"grounded":true,"sources":[{"title":"DEV-51","url":"https://example.test/browse/DEV-51"}]}"""
        val result = McpSchema.CallToolResult(
            listOf(McpSchema.TextContent(jsonText)),
            false,
            mapOf("grounded" to true),
            emptyMap()
        )
        every { client.callTool(any()) } returns result

        val output = callback.call(mapOf("issueKey" to "DEV-51"))

        assertEquals(
            jsonText,
            output,
            "Existing textual JSON output should remain unchanged"
        )
    }
}
