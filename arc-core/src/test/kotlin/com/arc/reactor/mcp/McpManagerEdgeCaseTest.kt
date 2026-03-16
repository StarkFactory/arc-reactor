package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.tool.ToolCallback
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
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP Manager 엣지 케이스 테스트.
 *
 * 재연결, 연결 해제 정리, 동시 작업, 저장소 실패 처리,
 * 종료 라이프사이클, 재등록 동작을 검증합니다.
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
        fun `disconnect then reconnect은(는) transition through correct states해야 한다`() = runBlocking {
            val manager = manager()
            manager.register(stdioServer("reconnect-server"))

            // Initial state
            assertEquals(McpServerStatus.PENDING, manager.getStatus("reconnect-server")) {
                "Initial status should be PENDING"
            }

            // 첫 번째 연결 시도 (실제 프로세스가 없으므로 실패)
            manager.connect("reconnect-server")
            assertEquals(McpServerStatus.FAILED, manager.getStatus("reconnect-server")) {
                "Status should be FAILED after failed connection attempt"
            }

            // Disconnect
            manager.disconnect("reconnect-server")
            assertEquals(McpServerStatus.DISCONNECTED, manager.getStatus("reconnect-server")) {
                "Status should be DISCONNECTED after disconnect"
            }

            // attempt 재연결
            manager.connect("reconnect-server")
            assertEquals(McpServerStatus.FAILED, manager.getStatus("reconnect-server")) {
                "Status should be FAILED after second failed connection attempt"
            }
        }

        @Test
        fun `disconnect은(는) clear tool callbacks해야 한다`() = runBlocking {
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
        fun `multiple disconnects은(는) not throw해야 한다`() = runBlocking {
            val manager = manager()
            manager.register(stdioServer("multi-dc-server"))

            // Disconnect multiple times —은(는) not throw해야 합니다
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
        fun `concurrent registers of different servers은(는) all succeed해야 한다`() = runBlocking {
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
        fun `concurrent connect and disconnect on same server은(는) not crash해야 한다`() = runBlocking {
            val manager = manager()
            manager.register(stdioServer("race-server"))

            // Launch connect and disconnect concurrently —은(는) not throw해야 합니다
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

            // Final state은(는) be one of the valid states해야 합니다
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
        fun `re-register same server name은(는) overwrite해야 한다`() {
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
        fun `register은(는) succeed when store save throws해야 한다`() {
            val failingStore = mockk<McpServerStore>(relaxed = true)
            every { failingStore.findByName(any()) } returns null
            every { failingStore.save(any()) } throws RuntimeException("DB write failed")
            every { failingStore.list() } returns emptyList()

            val manager = manager(store = failingStore)
            manager.register(stdioServer("resilient-server"))

            // Server은(는) still be in runtime despite store failure해야 합니다
            assertNotNull(manager.getStatus("resilient-server")) {
                "Server should be registered in runtime despite store failure"
            }
            assertEquals(McpServerStatus.PENDING, manager.getStatus("resilient-server")) {
                "Status should be PENDING"
            }
        }

        @Test
        fun `register은(는) skip store save when already in store해야 한다`() {
            val store = InMemoryMcpServerStore()
            val server = stdioServer("existing-server")
            store.save(server)

            val manager = manager(store = store)
            // not throw IllegalArgumentException from store.save duplicate check해야 합니다
            manager.register(server)

            assertEquals(1, store.list().size) {
                "Store should still have exactly 1 server"
            }
        }

        @Test
        fun `initializeFromStore은(는) handle store returning empty list해야 한다`() = runBlocking {
            val store = InMemoryMcpServerStore()
            val manager = manager(store = store)

            // 예외를 던지면 안 됩니다
            manager.initializeFromStore()

            assertTrue(manager.listServers().isEmpty()) {
                "No servers should be loaded from empty store"
            }
        }
    }

    @Nested
    inner class CloseLifecycle {

        @Test
        fun `close은(는) clear all servers and statuses해야 한다`() {
            val manager = manager()
            manager.register(stdioServer("server-1"))
            manager.register(stdioServer("server-2"))
            manager.register(sseServer("server-3"))

            manager.close()

            // 종료 후 내부 상태가 초기화되어야 합니다
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
        fun `close on fresh manager은(는) not throw해야 한다`() {
            val manager = manager()
            // not throw on empty manager해야 합니다
            manager.close()
        }

        @Test
        fun `close은(는) handle servers that were never connected해야 한다`() {
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
        fun `close after failed connections은(는) not throw해야 한다`() = runBlocking {
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

            // 예외를 던지면 안 됩니다
            manager.close()
        }
    }

    @Nested
    inner class TransportEdgeCases {

        @Test
        fun `SSE connect은(는) honor configured timeout해야 한다`() = runBlocking {
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
        fun `SSE with invalid URL은(는) fail gracefully해야 한다`() = runBlocking {
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
        fun `HTTP transport은(는) fail with informative status해야 한다`() = runBlocking {
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
        fun `STDIO with non-existent command은(는) fail gracefully해야 한다`() = runBlocking {
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
        fun `unregister은(는) remove from both runtime and store해야 한다`() = runBlocking {
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
        fun `unregister nonexistent server은(는) not throw해야 한다`() = runBlocking {
            val manager = manager()

            // 예외를 던지면 안 됩니다
            manager.unregister("nonexistent-server")
        }

        @Test
        fun `unregister은(는) disconnect if connected해야 한다`() = runBlocking {
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
    inner class ConnectionErrorHandling {

        @Test
        fun `handleConnectionError은(는) mark server FAILED and clear caches해야 한다`() {
            val manager = manager(reconnectionProperties = McpReconnectionProperties(enabled = false))
            manager.register(stdioServer("error-server"))
            manager.seedToolCallbacks("error-server", listOf(testCallback("error-tool")))
            val beforeError = manager.getAllToolCallbacks()

            // the server being in CONNECTED state를 시뮬레이션합니다
            manager.statuses["error-server"] = McpServerStatus.CONNECTED

            manager.handleConnectionError("error-server")

            assertEquals(McpServerStatus.FAILED, manager.getStatus("error-server")) {
                "Status should be FAILED after connection error"
            }
            assertTrue(manager.getToolCallbacks("error-server").isEmpty()) {
                "Tool callbacks should be cleared after connection error"
            }
            assertEquals(listOf("error-tool"), beforeError.map { it.name }) {
                "Expected snapshot warm-up to include seeded callback before connection error"
            }
            assertTrue(manager.getAllToolCallbacks().isEmpty()) {
                "Expected aggregated callback snapshot to be invalidated after connection error"
            }
        }

        @Test
        fun `handleConnectionError은(는) close the stale client to prevent resource leak해야 한다`() {
            val manager = manager(reconnectionProperties = McpReconnectionProperties(enabled = false))
            manager.register(stdioServer("leak-server"))
            manager.statuses["leak-server"] = McpServerStatus.CONNECTED

            // a mock McpSyncClient into the private clients map via reflection 주입
            val mockClient = mockk<io.modelcontextprotocol.client.McpSyncClient>(relaxed = true)
            val clientsField = DefaultMcpManager::class.java.getDeclaredField("clients")
            clientsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val clients = clientsField.get(manager) as ConcurrentHashMap<String, io.modelcontextprotocol.client.McpSyncClient>
            clients["leak-server"] = mockClient

            manager.handleConnectionError("leak-server")

            verify(atLeast = 1) { mockClient.closeGracefully() }
        }

        @Test
        fun `handleConnectionError은(는) be idempotent when server is already FAILED해야 한다`() {
            val manager = manager(reconnectionProperties = McpReconnectionProperties(enabled = false))
            manager.register(stdioServer("idempotent-server"))
            manager.statuses["idempotent-server"] = McpServerStatus.FAILED

            // Calling again은(는) not throw and should leave status as FAILED해야 합니다
            manager.handleConnectionError("idempotent-server")

            assertEquals(McpServerStatus.FAILED, manager.getStatus("idempotent-server")) {
                "Status should remain FAILED"
            }
        }

        @Test
        fun `handleConnectionError은(는) trigger reconnection when enabled해야 한다`() = runBlocking {
            val manager = manager(
                reconnectionProperties = McpReconnectionProperties(
                    enabled = true,
                    initialDelayMs = 50,
                    maxAttempts = 1
                )
            )
            manager.register(stdioServer("reconnect-on-error"))
            manager.statuses["reconnect-on-error"] = McpServerStatus.CONNECTED

            manager.handleConnectionError("reconnect-on-error")

            assertEquals(McpServerStatus.FAILED, manager.getStatus("reconnect-on-error")) {
                "Status should be FAILED immediately after connection error"
            }
        }
    }

    @Nested
    inner class SecurityAllowlistEdgeCases {

        @Test
        fun `allowlist은(는) be case-sensitive해야 한다`() {
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
        fun `multiple servers은(는) be filtered by allowlist해야 한다`() {
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

    private fun DefaultMcpManager.seedToolCallbacks(serverName: String, callbacks: List<ToolCallback>) {
        val field = DefaultMcpManager::class.java.getDeclaredField("toolCallbacksCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(this) as ConcurrentHashMap<String, List<ToolCallback>>
        cache[serverName] = callbacks
    }

    private fun testCallback(name: String): ToolCallback {
        return object : ToolCallback {
            override val name: String = name
            override val description: String = "test-$name"
            override suspend fun call(arguments: Map<String, Any?>): Any? = "ok"
        }
    }
}
