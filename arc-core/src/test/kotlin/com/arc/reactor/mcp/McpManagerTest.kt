package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class McpManagerTest {

    private fun manager(
        store: McpServerStore? = null,
        securityConfig: McpSecurityConfig = McpSecurityConfig(),
        reconnectionProperties: McpReconnectionProperties = McpReconnectionProperties(enabled = false)
    ) = DefaultMcpManager(
        connectionTimeoutMs = 300,
        securityConfig = securityConfig,
        store = store,
        reconnectionProperties = reconnectionProperties
    )

    @Test
    fun `should register MCP server`() {
        val manager = manager()

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
        val manager = manager()

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
    fun `syncRuntimeServer should update runtime config`() {
        val manager = manager()
        manager.register(
            McpServer(
                name = "sync-target",
                description = "before",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse"),
                autoConnect = false
            )
        )

        manager.syncRuntimeServer(
            McpServer(
                name = "sync-target",
                description = "after",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8082/sse"),
                autoConnect = true
            )
        )

        val synced = manager.listServers().first { it.name == "sync-target" }
        assertEquals("after", synced.description)
        assertEquals("http://localhost:8082/sse", synced.config["url"])
        assertTrue(synced.autoConnect, "autoConnect should be updated to true after syncRuntimeServer")
    }

    @Test
    fun `should return null status for unknown server`() {
        val manager = manager()

        val status = manager.getStatus("unknown-server")
        assertNull(status, "Unknown server should return null status")
    }

    @Test
    fun `should return empty callbacks when no servers connected`() {
        val manager = manager()

        val callbacks = manager.getAllToolCallbacks()
        assertTrue(callbacks.isEmpty()) { "Expected no callbacks when no servers connected, got: ${callbacks.size}" }
    }

    @Test
    fun `should fail connection for missing command config`() = runBlocking {
        val manager = manager()

        val server = McpServer(
            name = "invalid-server",
            transportType = McpTransportType.STDIO,
            config = emptyMap() // Missing command
        )

        manager.register(server)
        val connected = manager.connect("invalid-server")

        assertFalse(connected) { "Connection should fail for server with missing command config" }
        assertEquals(McpServerStatus.FAILED, manager.getStatus("invalid-server"))
    }

    @Test
    fun `should return false for connecting to unregistered server`() = runBlocking {
        val manager = manager()

        val connected = manager.connect("nonexistent-server")

        assertFalse(connected) { "Connection should fail for unregistered server" }
    }

    @Test
    fun `should support multiple server registrations`() {
        val manager = manager()

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
        val manager = manager()

        manager.register(McpServer(
            name = "test-server",
            transportType = McpTransportType.STDIO,
            config = mapOf("command" to "echo")
        ))

        val callbacks = manager.getToolCallbacks("test-server")
        assertTrue(callbacks.isEmpty()) { "Expected no callbacks for unconnected server, got: ${callbacks.size}" }
    }

    @Test
    fun `should fail SSE connection when url not provided`() = runBlocking {
        val manager = manager()
        val server = McpServer(
            name = "sse-server",
            transportType = McpTransportType.SSE,
            config = emptyMap() // Missing url
        )
        manager.register(server)
        val connected = manager.connect("sse-server")

        assertFalse(connected) { "SSE connection should fail when url is not provided" }
        assertEquals(McpServerStatus.FAILED, manager.getStatus("sse-server"))
    }

    @Test
    fun `should fail HTTP connection with unsupported transport`() = runBlocking {
        val manager = manager()
        val server = McpServer(
            name = "http-server",
            transportType = McpTransportType.HTTP,
            config = mapOf("url" to "http://localhost:8080")
        )
        manager.register(server)
        val connected = manager.connect("http-server")

        assertFalse(connected) { "HTTP connection should fail with unsupported transport" }
        assertEquals(McpServerStatus.FAILED, manager.getStatus("http-server"))
    }

    @Test
    fun `should handle disconnect for unconnected server gracefully`() = runBlocking {
        val manager = manager()

        manager.register(McpServer(
            name = "test-server",
            transportType = McpTransportType.STDIO,
            config = mapOf("command" to "echo")
        ))

        // Should not throw
        manager.disconnect("test-server")

        assertEquals(McpServerStatus.DISCONNECTED, manager.getStatus("test-server"))
    }

    @Nested
    inner class Allowlist {

        @Test
        fun `should reject server not in allowlist`() {
            val manager = manager(
                securityConfig = McpSecurityConfig(
                    allowedServerNames = setOf("trusted-server")
                )
            )

            manager.register(McpServer(
                name = "untrusted-server",
                transportType = McpTransportType.STDIO,
                config = mapOf("command" to "echo")
            ))

            assertTrue(manager.listServers().isEmpty()) {
                "Untrusted server should not be registered"
            }
        }

        @Test
        fun `should accept server in allowlist`() {
            val manager = manager(
                securityConfig = McpSecurityConfig(
                    allowedServerNames = setOf("trusted-server")
                )
            )

            manager.register(McpServer(
                name = "trusted-server",
                transportType = McpTransportType.STDIO,
                config = mapOf("command" to "echo")
            ))

            assertEquals(1, manager.listServers().size) {
                "Trusted server should be registered"
            }
        }

        @Test
        fun `should allow all servers when allowlist is empty`() {
            val manager = manager(
                securityConfig = McpSecurityConfig(allowedServerNames = emptySet())
            )

            manager.register(McpServer(
                name = "any-server",
                transportType = McpTransportType.STDIO,
                config = mapOf("command" to "echo")
            ))

            assertEquals(1, manager.listServers().size) {
                "Empty allowlist should allow all servers"
            }
        }
    }

    @Nested
    inner class StoreIntegration {

        @Test
        fun `register should persist server to store`() {
            val store = InMemoryMcpServerStore()
            val manager = manager(store = store)

            manager.register(McpServer(
                name = "persisted-server",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse")
            ))

            assertNotNull(store.findByName("persisted-server")) {
                "Registered server should be persisted to store"
            }
        }

        @Test
        fun `register should not duplicate in store`() {
            val store = InMemoryMcpServerStore()
            val manager = manager(store = store)

            val server = McpServer(
                name = "no-dup",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse")
            )

            // Pre-save to store
            store.save(server)

            // register should not throw even though it already exists in store
            manager.register(server)

            assertEquals(1, store.list().size) {
                "Store should still have exactly 1 server"
            }
        }

        @Test
        fun `unregister should remove from store and runtime`() = runBlocking {
            val store = InMemoryMcpServerStore()
            val manager = manager(store = store)

            manager.register(McpServer(
                name = "to-remove",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse")
            ))

            manager.unregister("to-remove")

            assertNull(store.findByName("to-remove")) { "Server should be removed from store" }
            assertTrue(manager.listServers().isEmpty()) { "Server should be removed from runtime" }
            assertNull(manager.getStatus("to-remove")) { "Status should be removed" }
        }

        @Test
        fun `listServers should return from store when available`() {
            val store = InMemoryMcpServerStore()
            store.save(McpServer(name = "store-server", transportType = McpTransportType.SSE))

            val manager = manager(store = store)
            // Don't call register, just rely on store

            val servers = manager.listServers()
            assertEquals(1, servers.size) { "Should list servers from store" }
            assertEquals("store-server", servers[0].name, "Should return store server")
        }

        @Test
        fun `initializeFromStore should load and auto-connect`() = runBlocking {
            val store = InMemoryMcpServerStore()
            store.save(McpServer(
                name = "auto-server",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse"),
                autoConnect = true
            ))
            store.save(McpServer(
                name = "manual-server",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8082/sse"),
                autoConnect = false
            ))

            val manager = manager(store = store)
            manager.initializeFromStore()

            // auto-connect server should have attempted connection (will fail since no real server)
            val autoStatus = manager.getStatus("auto-server")
            assertNotNull(autoStatus) { "Auto-connect server should have a status" }
            assertTrue(autoStatus == McpServerStatus.FAILED || autoStatus == McpServerStatus.CONNECTED) {
                "Auto-connect server should be FAILED (no real server) or CONNECTED"
            }

            // manual server should be PENDING (no connect attempted)
            assertEquals(McpServerStatus.PENDING, manager.getStatus("manual-server")) {
                "Manual server should remain PENDING"
            }
        }

        @Test
        fun `initializeFromStore should handle empty store`() = runBlocking {
            val store = InMemoryMcpServerStore()
            val manager = manager(store = store)

            // Should not throw
            manager.initializeFromStore()

            assertTrue(manager.listServers().isEmpty()) { "No servers should be loaded from empty store" }
        }
    }

    @Nested
    inner class OutputTruncation {

        @Test
        fun `McpSecurityConfig should have sensible defaults`() {
            val config = McpSecurityConfig()

            assertTrue(config.allowedServerNames.isEmpty()) {
                "Default allowlist should be empty (allow all)"
            }
            assertEquals(50_000, config.maxToolOutputLength) {
                "Default max output length should be 50,000 characters"
            }
        }

        @Test
        fun `McpSecurityConfig should accept custom values`() {
            val config = McpSecurityConfig(
                allowedServerNames = setOf("a", "b"),
                maxToolOutputLength = 10_000
            )

            assertEquals(2, config.allowedServerNames.size) {
                "Should store custom allowlist"
            }
            assertEquals(10_000, config.maxToolOutputLength) {
                "Should store custom max output length"
            }
        }
    }
}
