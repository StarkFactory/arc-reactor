package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.tool.ToolCallback
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * McpManager에 대한 테스트.
 *
 * MCP 서버 등록/연결/해제 관리를 검증합니다.
 */
class McpManagerTest {

    private fun manager(
        store: McpServerStore? = null,
        securityConfig: McpSecurityConfig = McpSecurityConfig(),
        securityConfigProvider: () -> McpSecurityConfig = { securityConfig },
        reconnectionProperties: McpReconnectionProperties = McpReconnectionProperties(enabled = false)
    ) = DefaultMcpManager(
        connectionTimeoutMs = 300,
        securityConfig = securityConfig,
        securityConfigProvider = securityConfigProvider,
        store = store,
        reconnectionProperties = reconnectionProperties
    )

    @Test
    fun `register MCP server해야 한다`() {
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
    fun `registration 후 report PENDING status해야 한다`() {
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
    fun `syncRuntimeServer은(는) update runtime config해야 한다`() {
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
    fun `unknown server에 대해 return null status해야 한다`() {
        val manager = manager()

        val status = manager.getStatus("unknown-server")
        assertNull(status, "Unknown server should return null status")
    }

    @Test
    fun `no servers connected일 때 return empty callbacks해야 한다`() {
        val manager = manager()

        val callbacks = manager.getAllToolCallbacks()
        assertTrue(callbacks.isEmpty()) { "Expected no callbacks when no servers connected, got: ${callbacks.size}" }
    }

    @Test
    fun `reuse aggregated callback snapshot until cache changes해야 한다`() {
        val manager = manager()
        manager.seedToolCallbacks(
            "cached-server",
            listOf(testCallback("tool-a"), testCallback("tool-b"))
        )

        val first = manager.getAllToolCallbacks()
        val second = manager.getAllToolCallbacks()

        assertSame(first, second, "Expected getAllToolCallbacks to reuse the cached snapshot instance")
        assertEquals(listOf("tool-a", "tool-b"), second.map { it.name }) {
            "Expected cached snapshot to preserve callback order"
        }
    }

    @Test
    fun `disconnect은(는) invalidate aggregated callback snapshot해야 한다`() = runBlocking {
        val manager = manager()
        manager.register(
            McpServer(
                name = "disconnect-server",
                transportType = McpTransportType.STDIO,
                config = mapOf("command" to "echo")
            )
        )
        manager.seedToolCallbacks("disconnect-server", listOf(testCallback("disconnect-tool")))

        val beforeDisconnect = manager.getAllToolCallbacks()
        manager.disconnect("disconnect-server")
        val afterDisconnect = manager.getAllToolCallbacks()

        assertEquals(listOf("disconnect-tool"), beforeDisconnect.map { it.name }) {
            "Expected snapshot warm-up to include seeded callback before disconnect"
        }
        assertTrue(afterDisconnect.isEmpty()) {
            "Expected aggregated callback snapshot to be invalidated after disconnect"
        }
    }

    @Test
    fun `missing command config에 대해 fail connection해야 한다`() = runBlocking {
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
    fun `connecting to unregistered server에 대해 return false해야 한다`() = runBlocking {
        val manager = manager()

        val connected = manager.connect("nonexistent-server")

        assertFalse(connected) { "Connection should fail for unregistered server" }
    }

    @Test
    fun `support multiple server registrations해야 한다`() {
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
    fun `specific unconnected server에 대해 return empty callbacks해야 한다`() {
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
    fun `url not provided일 때 fail SSE connection해야 한다`() = runBlocking {
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
    fun `unsupported transport로 fail HTTP connection해야 한다`() = runBlocking {
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
    fun `unconnected server gracefully에 대해 handle disconnect해야 한다`() = runBlocking {
        val manager = manager()

        manager.register(McpServer(
            name = "test-server",
            transportType = McpTransportType.STDIO,
            config = mapOf("command" to "echo")
        ))

        // 예외를 던지면 안 됩니다
        manager.disconnect("test-server")

        assertEquals(McpServerStatus.DISCONNECTED, manager.getStatus("test-server"))
    }

    @Nested
    inner class Allowlist {

        @Test
        fun `reject server not in allowlist해야 한다`() {
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
        fun `accept server in allowlist해야 한다`() {
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
        fun `allowlist is empty일 때 allow all servers해야 한다`() {
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
        fun `register은(는) persist server to store해야 한다`() {
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
        fun `register은(는) not duplicate in store해야 한다`() {
            val store = InMemoryMcpServerStore()
            val manager = manager(store = store)

            val server = McpServer(
                name = "no-dup",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse")
            )

            // Pre-save to store
            store.save(server)

            // register은(는) not throw even though it already exists in store해야 합니다
            manager.register(server)

            assertEquals(1, store.list().size) {
                "Store should still have exactly 1 server"
            }
        }

        @Test
        fun `unregister은(는) remove from store and runtime해야 한다`() = runBlocking {
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
        fun `listServers은(는) return from store when available해야 한다`() {
            val store = InMemoryMcpServerStore()
            store.save(McpServer(name = "store-server", transportType = McpTransportType.SSE))

            val manager = manager(store = store)
            // Don't call register, just rely on store

            val servers = manager.listServers()
            assertEquals(1, servers.size) { "Should list servers from store" }
            assertEquals("store-server", servers[0].name, "Should return store server")
        }

        @Test
        fun `initializeFromStore은(는) load and auto-connect해야 한다`() = runBlocking {
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

            // auto-connect server은(는) have attempted connection (will fail since no real server)해야 합니다
            val autoStatus = manager.getStatus("auto-server")
            assertNotNull(autoStatus) { "Auto-connect server should have a status" }
            assertTrue(autoStatus == McpServerStatus.FAILED || autoStatus == McpServerStatus.CONNECTED) {
                "Auto-connect server should be FAILED (no real server) or CONNECTED"
            }

            // manual server은(는) be PENDING (no connect attempted)해야 합니다
            assertEquals(McpServerStatus.PENDING, manager.getStatus("manual-server")) {
                "Manual server should remain PENDING"
            }
        }

        @Test
        fun `initializeFromStore은(는) handle empty store해야 한다`() = runBlocking {
            val store = InMemoryMcpServerStore()
            val manager = manager(store = store)

            // 예외를 던지면 안 됩니다
            manager.initializeFromStore()

            assertTrue(manager.listServers().isEmpty()) { "No servers should be loaded from empty store" }
        }

        @Test
        fun `initializeFromStore은(는) skip servers blocked by dynamic allowlist해야 한다`() = runBlocking {
            val store = InMemoryMcpServerStore()
            store.save(McpServer(name = "swagger", transportType = McpTransportType.SSE))
            store.save(McpServer(name = "trusted", transportType = McpTransportType.SSE))

            var dynamicConfig = McpSecurityConfig(allowedServerNames = setOf("trusted"))
            val manager = manager(
                store = store,
                securityConfigProvider = { dynamicConfig }
            )

            manager.initializeFromStore()

            assertNull(manager.getStatus("swagger")) {
                "Blocked stored server should not be loaded into runtime"
            }
            assertEquals(McpServerStatus.PENDING, manager.getStatus("trusted")) {
                "Allowlisted stored server should be available in runtime"
            }

            dynamicConfig = McpSecurityConfig(allowedServerNames = setOf("swagger"))
            manager.reapplySecurityPolicy()

            assertNull(manager.getStatus("trusted")) {
                "Previously allowlisted server should be evicted after allowlist tightening"
            }
            assertEquals(McpServerStatus.PENDING, manager.getStatus("swagger")) {
                "Newly allowlisted stored server should be loaded into runtime"
            }
        }
    }

    @Nested
    inner class OutputTruncation {

        @Test
        fun `McpSecurityConfig은(는) have sensible defaults해야 한다`() {
            val config = McpSecurityConfig()

            assertTrue(config.allowedServerNames.isEmpty()) {
                "Default allowlist should be empty (allow all)"
            }
            assertEquals(50_000, config.maxToolOutputLength) {
                "Default max output length should be 50,000 characters"
            }
        }

        @Test
        fun `McpSecurityConfig은(는) accept custom values해야 한다`() {
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

    private fun DefaultMcpManager.seedToolCallbacks(serverName: String, callbacks: List<ToolCallback>) {
        val field = DefaultMcpManager::class.java.getDeclaredField("toolCallbacksCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(this) as com.github.benmanes.caffeine.cache.Cache<String, List<ToolCallback>>
        cache.put(serverName, callbacks)
    }

    private fun testCallback(name: String): ToolCallback {
        return object : ToolCallback {
            override val name: String = name
            override val description: String = "test-$name"
            override suspend fun call(arguments: Map<String, Any?>): Any? = "ok"
        }
    }
}
