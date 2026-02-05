package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class McpManagerTest {

    @Test
    fun `should register MCP server`() {
        val manager = DefaultMcpManager()

        val server = McpServer(
            name = "test-server",
            description = "Test MCP Server",
            transportType = McpTransportType.STDIO,
            config = mapOf(
                "command" to "npx",
                "args" to listOf("-y", "@modelcontextprotocol/server-everything")
            )
        )

        manager.register(server)

        val servers = manager.listServers()
        assertEquals(1, servers.size)
        assertEquals("test-server", servers[0].name)
    }

    @Test
    fun `should report PENDING status after registration`() {
        val manager = DefaultMcpManager()

        val server = McpServer(
            name = "test-server",
            transportType = McpTransportType.STDIO,
            config = mapOf("command" to "echo")
        )

        manager.register(server)

        val status = manager.getStatus("test-server")
        assertEquals(McpServerStatus.PENDING, status)
    }

    @Test
    fun `should return null status for unknown server`() {
        val manager = DefaultMcpManager()

        val status = manager.getStatus("unknown-server")
        assertNull(status)
    }

    @Test
    fun `should return empty callbacks when no servers connected`() {
        val manager = DefaultMcpManager()

        val callbacks = manager.getAllToolCallbacks()
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun `should fail connection for missing command config`() = runBlocking {
        val manager = DefaultMcpManager()

        val server = McpServer(
            name = "invalid-server",
            transportType = McpTransportType.STDIO,
            config = emptyMap() // Missing command
        )

        manager.register(server)
        val connected = manager.connect("invalid-server")

        assertFalse(connected)
        assertEquals(McpServerStatus.FAILED, manager.getStatus("invalid-server"))
    }

    @Test
    fun `should return false for connecting to unregistered server`() = runBlocking {
        val manager = DefaultMcpManager()

        val connected = manager.connect("nonexistent-server")

        assertFalse(connected)
    }

    @Test
    fun `should support multiple server registrations`() {
        val manager = DefaultMcpManager()

        manager.register(McpServer(
            name = "server-1",
            transportType = McpTransportType.STDIO,
            config = mapOf("command" to "cmd1")
        ))

        manager.register(McpServer(
            name = "server-2",
            transportType = McpTransportType.SSE,
            config = mapOf("url" to "http://localhost:8080")
        ))

        manager.register(McpServer(
            name = "server-3",
            transportType = McpTransportType.HTTP,
            config = mapOf("url" to "http://localhost:9090")
        ))

        val servers = manager.listServers()
        assertEquals(3, servers.size)
    }

    @Test
    fun `should return empty callbacks for specific unconnected server`() {
        val manager = DefaultMcpManager()

        manager.register(McpServer(
            name = "test-server",
            transportType = McpTransportType.STDIO,
            config = mapOf("command" to "echo")
        ))

        val callbacks = manager.getToolCallbacks("test-server")
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun `should handle disconnect for unconnected server gracefully`() = runBlocking {
        val manager = DefaultMcpManager()

        manager.register(McpServer(
            name = "test-server",
            transportType = McpTransportType.STDIO,
            config = mapOf("command" to "echo")
        ))

        // Should not throw
        manager.disconnect("test-server")

        assertEquals(McpServerStatus.DISCONNECTED, manager.getStatus("test-server"))
    }
}
