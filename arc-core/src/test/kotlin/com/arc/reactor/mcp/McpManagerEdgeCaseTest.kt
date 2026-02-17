package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * MCP Manager edge case tests.
 *
 * Covers: reconnection, disconnect cleanup, concurrent operations,
 * store failure handling, close lifecycle, and re-registration behavior.
 */
class McpManagerEdgeCaseTest {

    private fun stdioServer(name: String, command: String? = null) = McpServer(
        name = name,
        transportType = McpTransportType.STDIO,
        config = command?.let { mapOf("command" to it) } ?: emptyMap()
    )

    private fun sseServer(name: String, url: String = "not-a-valid-url") = McpServer(
        name = name,
        transportType = McpTransportType.SSE,
        config = mapOf("url" to url)
    )

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

    @Nested
    inner class ReconnectionBehavior {

        @Test
        fun `disconnect then reconnect should transition through correct states`() = runBlocking {
            val manager = manager()
            manager.register(stdioServer("reconnect-server"))

            // Initial state
            assertEquals(McpServerStatus.PENDING, manager.getStatus("reconnect-server")) {
                "Initial status should be PENDING"
            }

            // First connect attempt (will fail since no real process)
            manager.connect("reconnect-server")
            assertEquals(McpServerStatus.FAILED, manager.getStatus("reconnect-server")) {
                "Status should be FAILED after failed connection attempt"
            }

            // Disconnect
            manager.disconnect("reconnect-server")
            assertEquals(McpServerStatus.DISCONNECTED, manager.getStatus("reconnect-server")) {
                "Status should be DISCONNECTED after disconnect"
            }

            // Reconnect attempt
            manager.connect("reconnect-server")
            assertEquals(McpServerStatus.FAILED, manager.getStatus("reconnect-server")) {
                "Status should be FAILED after second failed connection attempt"
            }
        }

        @Test
        fun `disconnect should clear tool callbacks`() = runBlocking {
            val manager = manager()
            manager.register(stdioServer("tool-server"))

            // Attempt connect (will fail, but verifies callback state)
            manager.connect("tool-server")
            manager.disconnect("tool-server")

            assertTrue(manager.getToolCallbacks("tool-server").isEmpty()) {
                "Tool callbacks should be empty after disconnect"
            }
            assertTrue(manager.getAllToolCallbacks().isEmpty()) {
                "All tool callbacks should be empty after disconnect"
            }
        }

        @Test
        fun `multiple disconnects should not throw`() = runBlocking {
            val manager = manager()
            manager.register(stdioServer("multi-dc-server"))

            // Disconnect multiple times — should not throw
            manager.disconnect("multi-dc-server")
            manager.disconnect("multi-dc-server")
            manager.disconnect("multi-dc-server")

            assertEquals(McpServerStatus.DISCONNECTED, manager.getStatus("multi-dc-server")) {
                "Status should be DISCONNECTED after multiple disconnects"
            }
        }
    }

    @Nested
    inner class ConcurrentOperations {

        @Test
        fun `concurrent registers of different servers should all succeed`() = runBlocking {
            val manager = manager()

            coroutineScope {
                val jobs = (1..10).map { i ->
                    async {
                        manager.register(stdioServer("concurrent-$i"))
                    }
                }
                jobs.awaitAll()
            }

            assertEquals(10, manager.listServers().size) {
                "All 10 concurrent registrations should succeed"
            }
        }

        @Test
        fun `concurrent connect and disconnect on same server should not crash`() = runBlocking {
            val manager = manager()
            manager.register(stdioServer("race-server"))

            // Launch connect and disconnect concurrently — should not throw
            coroutineScope {
                val jobs = (1..5).map { i ->
                    async {
                        if (i % 2 == 0) {
                            manager.connect("race-server")
                        } else {
                            manager.disconnect("race-server")
                        }
                    }
                }
                jobs.awaitAll()
            }

            // Final state should be one of the valid states
            val status = manager.getStatus("race-server")
            assertNotNull(status) { "Server should still have a status" }
            assertTrue(
                status == McpServerStatus.FAILED ||
                    status == McpServerStatus.DISCONNECTED ||
                    status == McpServerStatus.CONNECTED
            ) {
                "Final status should be valid, got: $status"
            }
        }

        @Test
        fun `re-register same server name should overwrite`() {
            val manager = manager()

            manager.register(stdioServer("dup-server", command = "cmd1"))
            manager.register(stdioServer("dup-server", command = "cmd2"))

            val servers = manager.listServers()
            assertEquals(1, servers.size) { "Should have exactly 1 server after re-register" }
            assertEquals("cmd2", servers[0].config["command"]) {
                "Server should have updated config from second registration"
            }
        }
    }

    @Nested
    inner class StoreFailureHandling {

        @Test
        fun `register should succeed when store save throws`() {
            val failingStore = mockk<McpServerStore>(relaxed = true)
            every { failingStore.findByName(any()) } returns null
            every { failingStore.save(any()) } throws RuntimeException("DB write failed")
            every { failingStore.list() } returns emptyList()

            val manager = manager(store = failingStore)
            manager.register(stdioServer("resilient-server"))

            // Server should still be in runtime despite store failure
            assertNotNull(manager.getStatus("resilient-server")) {
                "Server should be registered in runtime despite store failure"
            }
            assertEquals(McpServerStatus.PENDING, manager.getStatus("resilient-server")) {
                "Status should be PENDING"
            }
        }

        @Test
        fun `register should skip store save when already in store`() {
            val store = InMemoryMcpServerStore()
            val server = stdioServer("existing-server")
            store.save(server)

            val manager = manager(store = store)
            // Should not throw IllegalArgumentException from store.save duplicate check
            manager.register(server)

            assertEquals(1, store.list().size) {
                "Store should still have exactly 1 server"
            }
        }

        @Test
        fun `initializeFromStore should handle store returning empty list`() = runBlocking {
            val store = InMemoryMcpServerStore()
            val manager = manager(store = store)

            // Should not throw
            manager.initializeFromStore()

            assertTrue(manager.listServers().isEmpty()) {
                "No servers should be loaded from empty store"
            }
        }
    }

    @Nested
    inner class CloseLifecycle {

        @Test
        fun `close should clear all servers and statuses`() {
            val manager = manager()
            manager.register(stdioServer("server-1"))
            manager.register(stdioServer("server-2"))
            manager.register(sseServer("server-3"))

            manager.close()

            // After close, internal state should be cleared
            assertNull(manager.getStatus("server-1")) {
                "Status should be null after close"
            }
            assertNull(manager.getStatus("server-2")) {
                "Status should be null after close"
            }
            assertTrue(manager.getAllToolCallbacks().isEmpty()) {
                "All callbacks should be empty after close"
            }
        }

        @Test
        fun `close on fresh manager should not throw`() {
            val manager = manager()
            // Should not throw on empty manager
            manager.close()
        }

        @Test
        fun `close should handle servers that were never connected`() {
            val manager = manager()
            manager.register(stdioServer("never-connected-1"))
            manager.register(stdioServer("never-connected-2"))

            // close iterates over clients.keys — these servers have no clients
            manager.close()

            // Servers map cleared by close()
            assertNull(manager.getStatus("never-connected-1")) {
                "Status should be null after close"
            }
        }

        @Test
        fun `close after failed connections should not throw`() = runBlocking {
            val manager = manager()
            manager.register(stdioServer("fail-server-1"))
            manager.register(McpServer(
                name = "fail-server-2",
                transportType = McpTransportType.STDIO,
                config = emptyMap() // Missing command
            ))

            // Connect attempts — both will fail
            manager.connect("fail-server-1")
            manager.connect("fail-server-2")

            // Should not throw
            manager.close()
        }
    }

    @Nested
    inner class TransportEdgeCases {

        @Test
        fun `SSE connect should honor configured timeout`() = runBlocking {
            val manager = manager()
            manager.register(McpServer(
                name = "timeout-sse",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "not-a-valid-url")
            ))

            val start = System.currentTimeMillis()
            manager.connect("timeout-sse")
            val elapsed = System.currentTimeMillis() - start

            assertTrue(elapsed < 3000) {
                "SSE connect should fail quickly with configured timeout, took ${elapsed}ms"
            }
        }

        @Test
        fun `SSE with invalid URL should fail gracefully`() = runBlocking {
            val manager = manager()
            manager.register(McpServer(
                name = "bad-sse",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "not-a-valid-url")
            ))

            val connected = manager.connect("bad-sse")

            assertFalse(connected) { "Connection with invalid URL should fail" }
            assertEquals(McpServerStatus.FAILED, manager.getStatus("bad-sse")) {
                "Status should be FAILED for invalid URL"
            }
        }

        @Test
        fun `HTTP transport should fail with informative status`() = runBlocking {
            val manager = manager()
            manager.register(McpServer(
                name = "http-server",
                transportType = McpTransportType.HTTP,
                config = mapOf("url" to "http://localhost:9090")
            ))

            val connected = manager.connect("http-server")

            assertFalse(connected) { "HTTP transport should fail (not yet supported)" }
            assertEquals(McpServerStatus.FAILED, manager.getStatus("http-server")) {
                "Status should be FAILED for unsupported HTTP transport"
            }
        }

        @Test
        fun `STDIO with non-existent command should fail gracefully`() = runBlocking {
            val manager = manager()
            manager.register(McpServer(
                name = "bad-stdio",
                transportType = McpTransportType.STDIO,
                config = mapOf("command" to "/nonexistent/binary/that/does/not/exist")
            ))

            val connected = manager.connect("bad-stdio")

            assertFalse(connected) { "Connection with non-existent command should fail" }
            assertEquals(McpServerStatus.FAILED, manager.getStatus("bad-stdio")) {
                "Status should be FAILED for non-existent command"
            }
        }
    }

    @Nested
    inner class UnregisterBehavior {

        @Test
        fun `unregister should remove from both runtime and store`() = runBlocking {
            val store = InMemoryMcpServerStore()
            val manager = manager(store = store)

            manager.register(stdioServer("to-remove"))
            assertEquals(1, store.list().size) { "Server should be in store after register" }

            manager.unregister("to-remove")

            assertNull(manager.getStatus("to-remove")) {
                "Status should be null after unregister"
            }
            assertNull(store.findByName("to-remove")) {
                "Server should be removed from store"
            }
            assertTrue(manager.getToolCallbacks("to-remove").isEmpty()) {
                "Callbacks should be empty after unregister"
            }
        }

        @Test
        fun `unregister nonexistent server should not throw`() = runBlocking {
            val manager = manager()

            // Should not throw
            manager.unregister("nonexistent-server")
        }

        @Test
        fun `unregister should disconnect if connected`() = runBlocking {
            val manager = manager()
            manager.register(stdioServer("connected-removal"))

            // Attempt connect (will fail, but sets up internal state)
            manager.connect("connected-removal")

            manager.unregister("connected-removal")

            assertNull(manager.getStatus("connected-removal")) {
                "Status should be null after unregister"
            }
            assertTrue(manager.getAllToolCallbacks().isEmpty()) {
                "All callbacks should be empty after unregister"
            }
        }
    }

    @Nested
    inner class SecurityAllowlistEdgeCases {

        @Test
        fun `allowlist should be case-sensitive`() {
            val manager = manager(
                securityConfig = McpSecurityConfig(
                    allowedServerNames = setOf("Trusted-Server")
                )
            )

            manager.register(stdioServer("trusted-server")) // lowercase
            manager.register(stdioServer("Trusted-Server")) // exact match

            assertEquals(1, manager.listServers().size) {
                "Only exact-match server should be registered"
            }
            assertEquals("Trusted-Server", manager.listServers()[0].name) {
                "Registered server should be the exact-match one"
            }
        }

        @Test
        fun `multiple servers should be filtered by allowlist`() {
            val manager = manager(
                securityConfig = McpSecurityConfig(
                    allowedServerNames = setOf("allowed-1", "allowed-2")
                )
            )

            manager.register(stdioServer("allowed-1"))
            manager.register(stdioServer("allowed-2"))
            manager.register(stdioServer("blocked-1"))
            manager.register(stdioServer("blocked-2"))

            assertEquals(2, manager.listServers().size) {
                "Only 2 allowed servers should be registered"
            }
        }
    }
}
