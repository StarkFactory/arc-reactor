package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.mcp.DefaultMcpManager
import com.arc.reactor.mcp.InMemoryMcpServerStore
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.McpSecurityConfig
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

/**
 * McpServerController 시나리오 테스트.
 *
 * 실제 InMemoryMcpServerStore와 인증 컨텍스트를 위한
 * 모킹된 ServerWebExchange로 REST API 동작을 검증합니다.
 */
class McpServerControllerTest {

    private lateinit var store: McpServerStore
    private lateinit var manager: McpManager
    private lateinit var adminAuditStore: AdminAuditStore
    private lateinit var controller: McpServerController

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>(relaxed = true)
        val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN)
        every { exchange.attributes } returns attrs
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>(relaxed = true)
        val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER)
        every { exchange.attributes } returns attrs
        return exchange
    }

    @BeforeEach
    fun setup() {
        store = InMemoryMcpServerStore()
        manager = DefaultMcpManager(store = store)
        adminAuditStore = InMemoryAdminAuditStore()
        controller = McpServerController(manager, store, adminAuditStore, com.arc.reactor.agent.config.AgentProperties())
    }

    @Nested
    inner class ListServers {

        @Test
        fun `no servers registered일 때 return empty list해야 한다`() {
            val response = controller.listServers(adminExchange())
            assertEquals(HttpStatus.OK, response.statusCode) { "Admin list should return 200" }
            @Suppress("UNCHECKED_CAST")
            val result = response.body as List<McpServerResponse>
            assertTrue(result.isEmpty()) { "Expected empty list, got ${result.size} servers" }
        }

        @Test
        fun `status로 list registered servers해야 한다`() {
            manager.register(McpServer(
                name = "test-sse",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse")
            ))

            val response = controller.listServers(adminExchange())
            assertEquals(HttpStatus.OK, response.statusCode) { "Admin list should return 200" }
            @Suppress("UNCHECKED_CAST")
            val result = response.body as List<McpServerResponse>
            assertEquals(1, result.size) { "Expected 1 server" }
            assertEquals("test-sse", result[0].name)
            assertEquals("SSE", result[0].transportType)
            assertEquals("PENDING", result[0].status)
        }

        @Test
        fun `reject non-admin list해야 한다`() {
            val response = controller.listServers(userExchange())
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Non-admin should receive 403 when listing servers"
            }
        }
    }

    @Nested
    inner class RegisterServer {

        @Test
        fun `register server as admin해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "my-server",
                transportType = "SSE",
                config = mapOf("url" to "http://example.com:9090/sse"),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.CREATED, response.statusCode) {
                "Expected 201 CREATED, got ${response.statusCode}"
            }

            // persisted in store 확인
            val saved = store.findByName("my-server")
            assertNotNull(saved) { "Server should be persisted in store" }
            assertEquals(McpTransportType.SSE, saved!!.transportType)
            val audits = adminAuditStore.list()
            assertEquals(1, audits.size)
            assertEquals("mcp_server", audits.first().category)
            assertEquals("CREATE", audits.first().action)
        }

        @Test
        fun `reject duplicate server name해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "duplicate",
                transportType = "SSE",
                config = mapOf("url" to "http://example.com:8081/sse"),
                autoConnect = false
            )

            controller.registerServer(request, adminExchange())
            val duplicate = controller.registerServer(request, adminExchange())

            assertEquals(HttpStatus.CONFLICT, duplicate.statusCode) {
                "Expected 409 CONFLICT for duplicate name"
            }
        }

        @Test
        fun `reject non-admin register해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "forbidden-server",
                transportType = "SSE",
                autoConnect = false
            )

            val response = controller.registerServer(request, userExchange())
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Non-admin should get 403 FORBIDDEN"
            }
        }

        @Test
        fun `reject invalid transport type해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "invalid-transport",
                transportType = "SSEE",
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "Invalid transport type should return 400 BAD_REQUEST"
            }
        }

        @Test
        fun `reject unsupported http transport해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "http-server",
                transportType = "HTTP",
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "HTTP transport should be rejected with 400 BAD_REQUEST"
            }
        }

        @Test
        fun `surrounding spaces로 accept transport type해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "trimmed-transport",
                transportType = "  sSe  ",
                config = mapOf("url" to "http://example.com:8081/sse"),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.CREATED, response.statusCode) {
                "Trimmed transport type should be parsed successfully"
            }

            val saved = store.findByName("trimmed-transport")
            assertNotNull(saved) { "Server should be saved for valid trimmed transport type" }
            assertEquals(McpTransportType.SSE, saved!!.transportType) {
                "Trimmed and case-insensitive transport type should resolve to SSE"
            }
        }

        @Test
        fun `register realistic stdio server payload해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "filesystem-prod",
                description = "Filesystem MCP server for production-like scenario",
                transportType = "STDIO",
                config = mapOf(
                    "command" to "npx",
                    "args" to listOf("-y", "@modelcontextprotocol/server-filesystem", "/var/data")
                ),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.CREATED, response.statusCode) {
                "Realistic STDIO registration payload should succeed"
            }

            val saved = store.findByName("filesystem-prod")
            assertNotNull(saved) { "Registered STDIO server should be persisted in store" }
            assertEquals(McpTransportType.STDIO, saved!!.transportType) {
                "STDIO transport should be persisted"
            }
            assertEquals("npx", saved.config["command"]) {
                "STDIO command should be preserved in persisted config"
            }
            assertEquals(
                listOf("-y", "@modelcontextprotocol/server-filesystem", "/var/data"),
                saved.config["args"]
            ) {
                "STDIO args should be preserved in persisted config"
            }
        }

        @Test
        fun `private IP url로 reject SSE server해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "ssrf-private",
                transportType = "SSE",
                config = mapOf("url" to "http://10.0.0.1:8080/sse"),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "SSE URL pointing to private IP should be rejected with 400"
            }
        }

        @Test
        fun `cloud metadata url로 reject SSE server해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "ssrf-metadata",
                transportType = "SSE",
                config = mapOf("url" to "http://169.254.169.254/latest/meta-data/"),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "SSE URL pointing to link-local metadata should be rejected with 400"
            }
        }

        @Test
        fun `localhost url로 reject SSE server해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "ssrf-localhost",
                transportType = "SSE",
                config = mapOf("url" to "http://127.0.0.1:8080/sse"),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "SSE URL pointing to localhost should be rejected with 400"
            }
        }

        @Test
        fun `file scheme url로 reject SSE server해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "ssrf-file",
                transportType = "SSE",
                config = mapOf("url" to "file:///etc/passwd"),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "SSE URL with file:// scheme should be rejected with 400"
            }
        }

        @Test
        fun `private IP adminUrl로 reject SSE server해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "ssrf-admin-url",
                transportType = "SSE",
                config = mapOf(
                    "url" to "http://example.com:8081/sse",
                    "adminUrl" to "http://169.254.169.254/admin"
                ),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "SSE adminUrl pointing to link-local private IP should be rejected with 400"
            }
        }

        @Test
        fun `STDIO transport에 대해 skip SSRF validation해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "stdio-no-ssrf",
                transportType = "STDIO",
                config = mapOf("command" to "npx", "args" to listOf("-y", "server")),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.CREATED, response.statusCode) {
                "STDIO transport should not be subject to SSRF validation"
            }
        }

        @Test
        fun `192_168 private url로 reject SSE server해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "ssrf-192",
                transportType = "SSE",
                config = mapOf("url" to "http://192.168.1.1:8080/sse"),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "SSE URL pointing to 192.168.x.x should be rejected with 400"
            }
        }

        @Test
        fun `172_16 private url로 reject SSE server해야 한다`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "ssrf-172",
                transportType = "SSE",
                config = mapOf("url" to "http://172.16.0.1:8080/sse"),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "SSE URL pointing to 172.16.x.x should be rejected with 400"
            }
        }
    }

    @Nested
    inner class GetServer {

        @Test
        fun `return server details해야 한다`() {
            manager.register(McpServer(
                name = "detail-test",
                description = "Test server",
                transportType = McpTransportType.STDIO,
                config = mapOf("command" to "echo"),
                autoConnect = false
            ))

            val response = controller.getServer("detail-test", adminExchange())
            assertEquals(HttpStatus.OK, response.statusCode) {
                "Expected 200 OK"
            }

            val body = response.body as McpServerDetailResponse
            assertEquals("detail-test", body.name)
            assertEquals("Test server", body.description)
            assertEquals("STDIO", body.transportType)
            assertEquals("PENDING", body.status)
            assertTrue(body.tools.isEmpty()) { "Unconnected server should have no tools" }
        }

        @Test
        fun `mask sensitive config values recursively in server details해야 한다`() {
            manager.register(McpServer(
                name = "masked-config",
                transportType = McpTransportType.SSE,
                config = mapOf(
                    "url" to "http://localhost:8081/sse",
                    "apiKey" to "secret-key-123",
                    "adminToken" to "token-xyz",
                    "headers" to mapOf(
                        "Authorization" to "Bearer nested-secret",
                        "X-Trace-Id" to "trace-123"
                    ),
                    "targets" to listOf(
                        mapOf("accessToken" to "list-secret"),
                        mapOf("name" to "safe-target")
                    )
                ),
                autoConnect = false
            ))

            val response = controller.getServer("masked-config", adminExchange())
            assertEquals(HttpStatus.OK, response.statusCode) { "Expected 200 OK" }

            val body = response.body as McpServerDetailResponse
            assertEquals("http://localhost:8081/sse", body.config["url"])
            assertEquals("********", body.config["apiKey"])
            assertEquals("********", body.config["adminToken"])
            val headers = body.config["headers"] as Map<*, *>
            assertEquals("********", headers["Authorization"])
            assertEquals("trace-123", headers["X-Trace-Id"])
            val targets = body.config["targets"] as List<*>
            val firstTarget = targets[0] as Map<*, *>
            val secondTarget = targets[1] as Map<*, *>
            assertEquals("********", firstTarget["accessToken"])
            assertEquals("safe-target", secondTarget["name"])
        }

        @Test
        fun `unknown server에 대해 return 404해야 한다`() {
            val response = controller.getServer("nonexistent", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "Expected 404 NOT_FOUND for unknown server"
            }
        }

        @Test
        fun `reject non-admin get detail해야 한다`() {
            val response = controller.getServer("detail-test", userExchange())
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Non-admin should receive 403 for server detail"
            }
        }
    }

    @Nested
    inner class UpdateServer {

        @Test
        fun `update server config as admin해야 한다`() = runTest {
            manager.register(McpServer(
                name = "update-me",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://example.com:8080/sse"),
                autoConnect = false
            ))

            val updateReq = UpdateMcpServerRequest(
                description = "Updated description",
                config = mapOf("url" to "http://example.org:9090/sse")
            )

            val response = controller.updateServer("update-me", updateReq, adminExchange())
            assertEquals(HttpStatus.OK, response.statusCode) {
                "Expected 200 OK for update"
            }

            val updated = store.findByName("update-me")!!
            assertEquals("Updated description", updated.description)
            assertEquals("http://example.org:9090/sse", updated.config["url"])
        }

        @Test
        fun `updating nonexistent server일 때 return 404해야 한다`() = runTest {
            val response = controller.updateServer(
                "ghost",
                UpdateMcpServerRequest(description = "test"),
                adminExchange()
            )
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "Expected 404 for nonexistent server update"
            }
        }

        @Test
        fun `reject non-admin update해야 한다`() = runTest {
            manager.register(McpServer(
                name = "no-update",
                transportType = McpTransportType.SSE,
                autoConnect = false
            ))

            val response = controller.updateServer(
                "no-update",
                UpdateMcpServerRequest(description = "hacked"),
                userExchange()
            )
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Non-admin should get 403"
            }
        }

        @Test
        fun `transportType is omitted일 때 preserve existing transport해야 한다`() = runTest {
            manager.register(McpServer(
                name = "preserve-transport",
                transportType = McpTransportType.STDIO,
                config = mapOf("command" to "echo"),
                autoConnect = false
            ))

            val response = controller.updateServer(
                "preserve-transport",
                UpdateMcpServerRequest(description = "keep transport"),
                adminExchange()
            )
            assertEquals(HttpStatus.OK, response.statusCode) {
                "Update should succeed when transportType is omitted"
            }

            val updated = store.findByName("preserve-transport")
            assertNotNull(updated) { "Updated server should exist in store" }
            assertEquals(McpTransportType.STDIO, updated!!.transportType) {
                "Omitted transportType should preserve existing transport"
            }
        }

        @Test
        fun `reject invalid transport type on update해야 한다`() = runTest {
            manager.register(McpServer(
                name = "invalid-update-transport",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse"),
                autoConnect = false
            ))

            val response = controller.updateServer(
                "invalid-update-transport",
                UpdateMcpServerRequest(transportType = "invalid"),
                adminExchange()
            )
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "Invalid update transport should return 400 BAD_REQUEST"
            }
        }

        @Test
        fun `reject unsupported http transport on update해야 한다`() = runTest {
            manager.register(McpServer(
                name = "http-update-transport",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse"),
                autoConnect = false
            ))

            val response = controller.updateServer(
                "http-update-transport",
                UpdateMcpServerRequest(transportType = "HTTP"),
                adminExchange()
            )
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "HTTP update transport should return 400 BAD_REQUEST"
            }
        }

        @Test
        fun `accept trimmed transport type on update해야 한다`() = runTest {
            manager.register(McpServer(
                name = "trim-update-transport",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse"),
                autoConnect = false
            ))

            val response = controller.updateServer(
                "trim-update-transport",
                UpdateMcpServerRequest(transportType = "  stdio "),
                adminExchange()
            )
            assertEquals(HttpStatus.OK, response.statusCode) {
                "Trimmed transport type should be parsed during update"
            }

            val updated = store.findByName("trim-update-transport")
            assertNotNull(updated) { "Updated server should exist after successful update" }
            assertEquals(McpTransportType.STDIO, updated!!.transportType) {
                "Trimmed transport type should update server to STDIO"
            }
        }

        @Test
        fun `SSRF url on SSE transport로 reject update해야 한다`() = runTest {
            manager.register(McpServer(
                name = "ssrf-update",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://example.com:8081/sse"),
                autoConnect = false
            ))

            val response = controller.updateServer(
                "ssrf-update",
                UpdateMcpServerRequest(config = mapOf("url" to "http://169.254.169.254/latest/meta-data/")),
                adminExchange()
            )
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "Update with SSRF URL should return 400 BAD_REQUEST"
            }
        }

        @Test
        fun `store and manager are decoupled일 때 sync runtime manager state해야 한다`() = runTest {
            val runtimeManager = DefaultMcpManager()
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(runtimeManager, persistentStore, InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties())

            val original = McpServer(
                name = "sync-runtime",
                description = "old-description",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://example.com:8081/sse"),
                autoConnect = false
            )
            runtimeManager.register(original)
            persistentStore.save(original)

            val response = localController.updateServer(
                "sync-runtime",
                UpdateMcpServerRequest(description = "new-description"),
                adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode) {
                "Update should succeed"
            }
            val runtimeServer = runtimeManager.listServers().first { it.name == "sync-runtime" }
            assertEquals("new-description", runtimeServer.description) {
                "Runtime manager state should be updated with latest store config"
            }
        }

        @Test
        fun `connection config changes일 때 reconnect connected server해야 한다`() = runTest {
            val runtimeManager = mockk<McpManager>(relaxed = true)
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(runtimeManager, persistentStore, InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties())

            val original = McpServer(
                name = "reconnect-me",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://example.com:8080/sse"),
                autoConnect = true
            )
            persistentStore.save(original)

            every { runtimeManager.getStatus("reconnect-me") } returns
                com.arc.reactor.mcp.model.McpServerStatus.CONNECTED
            coEvery { runtimeManager.disconnect("reconnect-me") } returns Unit
            coEvery { runtimeManager.connect("reconnect-me") } returns true

            val response = localController.updateServer(
                "reconnect-me",
                UpdateMcpServerRequest(config = mapOf("url" to "http://example.org:9090/sse")),
                adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode)
            verify(exactly = 1) { runtimeManager.syncRuntimeServer(any()) }
            coVerify(exactly = 1) { runtimeManager.disconnect("reconnect-me") }
            coVerify(exactly = 1) { runtimeManager.connect("reconnect-me") }
        }

        @Test
        fun `only description changes일 때 not reconnect connected server해야 한다`() = runTest {
            val runtimeManager = mockk<McpManager>(relaxed = true)
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(runtimeManager, persistentStore, InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties())

            persistentStore.save(
                McpServer(
                    name = "desc-only",
                    description = "before",
                    transportType = McpTransportType.SSE,
                    config = mapOf("url" to "http://example.com:8080/sse"),
                    autoConnect = true
                )
            )

            every { runtimeManager.getStatus("desc-only") } returns com.arc.reactor.mcp.model.McpServerStatus.CONNECTED

            val response = localController.updateServer(
                "desc-only",
                UpdateMcpServerRequest(description = "after"),
                adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode)
            verify(exactly = 1) { runtimeManager.syncRuntimeServer(any()) }
            coVerify(exactly = 0) { runtimeManager.disconnect(any()) }
            coVerify(exactly = 0) { runtimeManager.connect(any()) }
        }

        @Test
        fun `autoConnect is enabled from disconnected state일 때 connect server after update해야 한다`() = runTest {
            val runtimeManager = mockk<McpManager>(relaxed = true)
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(runtimeManager, persistentStore, InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties())

            persistentStore.save(
                McpServer(
                    name = "auto-connect-me",
                    transportType = McpTransportType.SSE,
                    config = mapOf("url" to "http://example.com:8080/sse"),
                    autoConnect = false
                )
            )

            every { runtimeManager.getStatus("auto-connect-me") } returns
                com.arc.reactor.mcp.model.McpServerStatus.DISCONNECTED
            coEvery { runtimeManager.connect("auto-connect-me") } returns true

            val response = localController.updateServer(
                "auto-connect-me",
                UpdateMcpServerRequest(autoConnect = true),
                adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode)
            coVerify(exactly = 1) { runtimeManager.connect("auto-connect-me") }
            coVerify(exactly = 0) { runtimeManager.disconnect(any()) }
        }
    }

    @Nested
    inner class DeleteServer {

        @Test
        fun `delete server as admin해야 한다`() = runTest {
            manager.register(McpServer(
                name = "delete-me",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse"),
                autoConnect = false
            ))

            val response = controller.deleteServer("delete-me", adminExchange())
            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) {
                "Expected 204 NO_CONTENT"
            }

            assertNull(store.findByName("delete-me")) { "Server should be removed from store" }
            assertTrue(manager.listServers().isEmpty()) { "Server should be removed from manager" }
        }

        @Test
        fun `deleting nonexistent server일 때 return 404해야 한다`() = runTest {
            val response = controller.deleteServer("ghost", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "Expected 404 for nonexistent server delete"
            }
        }

        @Test
        fun `reject non-admin delete해야 한다`() = runTest {
            manager.register(McpServer(
                name = "protected",
                transportType = McpTransportType.SSE,
                autoConnect = false
            ))

            val response = controller.deleteServer("protected", userExchange())
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Non-admin should get 403"
            }
        }
    }

    @Nested
    inner class ConnectDisconnect {

        @Test
        fun `connect은(는) return 503 when server has no valid config해야 한다`() = runTest {
            manager.register(McpServer(
                name = "bad-config",
                transportType = McpTransportType.SSE,
                config = emptyMap(), // Missing url
                autoConnect = false
            ))

            val response = controller.connectServer("bad-config", adminExchange())
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode) {
                "Expected 503 for connection failure"
            }
        }

        @Test
        fun `connect은(는) return 404 for unknown server해야 한다`() = runTest {
            val response = controller.connectServer("nonexistent", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "Expected 404 for unknown server"
            }
        }

        @Test
        fun `disconnect은(는) return 404 for unknown server해야 한다`() = runTest {
            val response = controller.disconnectServer("nonexistent", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "Expected 404 for unknown server"
            }
        }

        @Test
        fun `disconnect은(는) succeed for registered server해야 한다`() = runTest {
            manager.register(McpServer(
                name = "disc-test",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse"),
                autoConnect = false
            ))

            val response = controller.disconnectServer("disc-test", adminExchange())
            assertEquals(HttpStatus.OK, response.statusCode) {
                "Expected 200 OK for disconnect"
            }
        }
    }

    @Nested
    inner class FullLifecycleScenario {

        @Test
        fun `then update then delete lifecycle를 등록한다`() = runTest {
            val exchange = adminExchange()

            // 1. 등록
            val registerReq = RegisterMcpServerRequest(
                name = "lifecycle-server",
                description = "Initial",
                transportType = "SSE",
                config = mapOf("url" to "http://example.com:8081/sse"),
                autoConnect = false
            )
            val created = controller.registerServer(registerReq, exchange)
            assertEquals(HttpStatus.CREATED, created.statusCode) { "Step 1: Register should succeed" }

            // 2. 목록에서 확인
            val listResponse = controller.listServers(exchange)
            assertEquals(HttpStatus.OK, listResponse.statusCode) { "Step 2: list should return 200" }
            @Suppress("UNCHECKED_CAST")
            val list = listResponse.body as List<McpServerResponse>
            assertEquals(1, list.size) { "Step 2: Should have 1 server" }
            assertEquals("lifecycle-server", list[0].name)

            // 3. 상세 조회
            val detail = controller.getServer("lifecycle-server", exchange)
            assertEquals(HttpStatus.OK, detail.statusCode) { "Step 3: Get should succeed" }
            assertEquals("Initial", (detail.body as McpServerDetailResponse).description)

            // 4. 업데이트
            val updated = controller.updateServer(
                "lifecycle-server",
                UpdateMcpServerRequest(description = "Updated"),
                exchange
            )
            assertEquals(HttpStatus.OK, updated.statusCode) { "Step 4: Update should succeed" }

            // 5. 업데이트 확인
            val afterUpdate = controller.getServer("lifecycle-server", exchange)
            assertEquals("Updated", (afterUpdate.body as McpServerDetailResponse).description) {
                "Step 5: Description should be updated"
            }

            // 6. 삭제
            val deleted = controller.deleteServer("lifecycle-server", exchange)
            assertEquals(HttpStatus.NO_CONTENT, deleted.statusCode) { "Step 6: Delete should succeed" }

            // 7. 삭제 확인
            val afterDeleteListResponse = controller.listServers(exchange)
            @Suppress("UNCHECKED_CAST")
            val afterDeleteList = afterDeleteListResponse.body as List<McpServerResponse>
            assertTrue(afterDeleteList.isEmpty()) { "Step 7: List should be empty after delete" }
        }

        @Test
        fun `security config은(는) filter servers through allowlist해야 한다`() = runTest {
            val secureStore = InMemoryMcpServerStore()
            val secureManager = DefaultMcpManager(
                securityConfig = McpSecurityConfig(allowedServerNames = setOf("trusted")),
                store = secureStore
            )
            val secureController = McpServerController(secureManager, secureStore, InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties())

            // allowed server 등록
            val trustedReq = RegisterMcpServerRequest(
                name = "trusted",
                transportType = "SSE",
                config = mapOf("url" to "http://example.com:8081/sse"),
                autoConnect = false
            )
            val trustedResp = secureController.registerServer(trustedReq, adminExchange())
            assertEquals(HttpStatus.CREATED, trustedResp.statusCode) {
                "Trusted server should register"
            }

            // List은(는) have only trusted해야 합니다
            val listResponse = secureController.listServers(adminExchange())
            @Suppress("UNCHECKED_CAST")
            val list = listResponse.body as List<McpServerResponse>
            assertEquals(1, list.size) { "Only trusted server should be listed" }
            assertEquals("trusted", list[0].name)
        }

        @Test
        fun `register은(는) return bad request when server is blocked by allowlist해야 한다`() = runTest {
            val secureStore = InMemoryMcpServerStore()
            val secureManager = DefaultMcpManager(
                securityConfig = McpSecurityConfig(allowedServerNames = setOf("trusted")),
                store = secureStore
            )
            val secureController = McpServerController(secureManager, secureStore, InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties())

            val blockedReq = RegisterMcpServerRequest(
                name = "untrusted",
                transportType = "SSE",
                config = mapOf("url" to "http://example.com:8081/sse"),
                autoConnect = false
            )

            val blockedResp = secureController.registerServer(blockedReq, adminExchange())
            assertEquals(HttpStatus.BAD_REQUEST, blockedResp.statusCode) {
                "Server blocked by allowlist should return 400 BAD_REQUEST"
            }

            assertNull(secureStore.findByName("untrusted")) {
                "Blocked server should not be persisted to store"
            }
        }

        @Test
        fun `register은(는) persist when runtime manager and store are decoupled해야 한다`() = runTest {
            val runtimeManager = DefaultMcpManager()
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(runtimeManager, persistentStore, InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties())

            val request = RegisterMcpServerRequest(
                name = "decoupled-register",
                transportType = "SSE",
                config = mapOf("url" to "http://example.com:8081/sse"),
                autoConnect = false
            )

            val response = localController.registerServer(request, adminExchange())
            assertEquals(HttpStatus.CREATED, response.statusCode) {
                "Register should succeed even when manager and store are decoupled"
            }
            assertNotNull(persistentStore.findByName("decoupled-register")) {
                "Register endpoint must persist server into controller store"
            }

            val detailResponse = localController.getServer("decoupled-register", adminExchange())
            assertEquals(HttpStatus.OK, detailResponse.statusCode) {
                "Get server should succeed after decoupled register persistence"
            }
        }
    }
}
