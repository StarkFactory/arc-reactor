package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.tool.ToolCallback
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class McpManagerTypeTest {

    @Test
    fun `getAllToolCallbacks should return List of ToolCallback`() {
        val manager = DefaultMcpManager()
        val result: List<ToolCallback> = manager.getAllToolCallbacks()
        assertTrue(result.isEmpty()) { "Expected empty tool callbacks list, got: ${result.size}" }
    }

    @Test
    fun `getToolCallbacks should return List of ToolCallback`() {
        val manager = DefaultMcpManager()
        val result: List<ToolCallback> = manager.getToolCallbacks("nonexistent")
        assertTrue(result.isEmpty()) { "Expected empty tool callbacks for nonexistent server, got: ${result.size}" }
    }

    @Test
    fun `should register server without connecting`() {
        val manager = DefaultMcpManager()
        manager.register(McpServer(
            name = "test-server",
            transportType = McpTransportType.STDIO,
            config = mapOf("command" to "echo")
        ))
        assertEquals(McpServerStatus.PENDING, manager.getStatus("test-server"))
        assertEquals(1, manager.listServers().size)
    }
}
