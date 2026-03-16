package com.arc.reactor.mcp

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MCP 도구 콜백에 대한 테스트.
 *
 * MCP 도구 콜백의 기본 동작을 검증합니다.
 */
class McpToolCallbackTest {

    @Test
    fun `onConnectionError when callTool throws를 호출한다`() = runBlocking {
        val client = mockk<McpSyncClient>()
        var errorCallbackInvoked = false
        val callback = McpToolCallback(
            client = client,
            name = "jira_list_projects",
            description = "List projects",
            mcpInputSchema = null,
            onConnectionError = { errorCallbackInvoked = true }
        )
        every { client.callTool(any()) } throws RuntimeException("Connection reset by peer")

        val output = callback.call(emptyMap())

        assertTrue(errorCallbackInvoked, "onConnectionError must be invoked when callTool throws")
        assertTrue(output.toString().startsWith("Error:"), "Error string must be returned to the caller")
    }

    @Test
    fun `invoke onConnectionError on successful call하지 않는다`() = runBlocking {
        val client = mockk<McpSyncClient>()
        var errorCallbackInvoked = false
        val callback = McpToolCallback(
            client = client,
            name = "jira_list_projects",
            description = "List projects",
            mcpInputSchema = null,
            onConnectionError = { errorCallbackInvoked = true }
        )
        val result = McpSchema.CallToolResult(
            listOf(McpSchema.TextContent("""{"ok":true}""")),
            false,
            null,
            emptyMap()
        )
        every { client.callTool(any()) } returns result

        callback.call(emptyMap())

        assertTrue(!errorCallbackInvoked, "onConnectionError must NOT be invoked on successful call")
    }

    @Test
    fun `text output does not carry machine-readable payload일 때 structured content를 반환한다`() = runBlocking {
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
    fun `it already contains machine-readable payload일 때 keeps textual json output`() = runBlocking {
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
