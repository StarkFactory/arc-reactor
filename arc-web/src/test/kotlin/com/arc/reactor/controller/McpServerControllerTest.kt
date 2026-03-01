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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

/**
 * McpServerController scenario tests.
 *
 * Tests REST API behavior with real InMemoryMcpServerStore
 * and mocked ServerWebExchange for auth context.
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
        controller = McpServerController(manager, store, adminAuditStore)
    }

    @Nested
    inner class ListServers {

        @Test
        fun `should return empty list when no servers registered`() {
            val response = controller.listServers(adminExchange())
            assertEquals(HttpStatus.OK, response.statusCode) { "Admin list should return 200" }
            @Suppress("UNCHECKED_CAST")
            val result = response.body as List<McpServerResponse>
            assertTrue(result.isEmpty()) { "Expected empty list, got ${result.size} servers" }
        }

        @Test
        fun `should list registered servers with status`() {
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
        fun `should reject non-admin list`() {
            val response = controller.listServers(userExchange())
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Non-admin should receive 403 when listing servers"
            }
        }
    }

    @Nested
    inner class RegisterServer {

        @Test
        fun `should register server as admin`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "my-server",
                transportType = "SSE",
                config = mapOf("url" to "http://localhost:9090/sse"),
                autoConnect = false
            )

            val response = controller.registerServer(request, adminExchange())
            assertEquals(HttpStatus.CREATED, response.statusCode) {
                "Expected 201 CREATED, got ${response.statusCode}"
            }

            // Verify persisted in store
            val saved = store.findByName("my-server")
            assertNotNull(saved) { "Server should be persisted in store" }
            assertEquals(McpTransportType.SSE, saved!!.transportType)
            val audits = adminAuditStore.list()
            assertEquals(1, audits.size)
            assertEquals("mcp_server", audits.first().category)
            assertEquals("CREATE", audits.first().action)
        }

        @Test
        fun `should reject duplicate server name`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "duplicate",
                transportType = "SSE",
                config = mapOf("url" to "http://localhost:8081/sse"),
                autoConnect = false
            )

            controller.registerServer(request, adminExchange())
            val duplicate = controller.registerServer(request, adminExchange())

            assertEquals(HttpStatus.CONFLICT, duplicate.statusCode) {
                "Expected 409 CONFLICT for duplicate name"
            }
        }

        @Test
        fun `should reject non-admin register`() = runTest {
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
        fun `should reject invalid transport type`() = runTest {
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
        fun `should reject unsupported http transport`() = runTest {
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
        fun `should accept transport type with surrounding spaces`() = runTest {
            val request = RegisterMcpServerRequest(
                name = "trimmed-transport",
                transportType = "  sSe  ",
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
        fun `should register realistic stdio server payload`() = runTest {
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
    }

    @Nested
    inner class GetServer {

        @Test
        fun `should return server details`() {
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
        fun `should return 404 for unknown server`() {
            val response = controller.getServer("nonexistent", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "Expected 404 NOT_FOUND for unknown server"
            }
        }

        @Test
        fun `should reject non-admin get detail`() {
            val response = controller.getServer("detail-test", userExchange())
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Non-admin should receive 403 for server detail"
            }
        }
    }

    @Nested
    inner class UpdateServer {

        @Test
        fun `should update server config as admin`() {
            manager.register(McpServer(
                name = "update-me",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://old:8080/sse"),
                autoConnect = false
            ))

            val updateReq = UpdateMcpServerRequest(
                description = "Updated description",
                config = mapOf("url" to "http://new:9090/sse")
            )

            val response = controller.updateServer("update-me", updateReq, adminExchange())
            assertEquals(HttpStatus.OK, response.statusCode) {
                "Expected 200 OK for update"
            }

            val updated = store.findByName("update-me")!!
            assertEquals("Updated description", updated.description)
            assertEquals("http://new:9090/sse", updated.config["url"])
        }

        @Test
        fun `should return 404 when updating nonexistent server`() {
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
        fun `should reject non-admin update`() {
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
        fun `should preserve existing transport when transportType is omitted`() {
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
        fun `should reject invalid transport type on update`() {
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
        fun `should reject unsupported http transport on update`() {
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
        fun `should accept trimmed transport type on update`() {
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
        fun `should sync runtime manager state when store and manager are decoupled`() {
            val runtimeManager = DefaultMcpManager()
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(runtimeManager, persistentStore, InMemoryAdminAuditStore())

            val original = McpServer(
                name = "sync-runtime",
                description = "old-description",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse"),
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
    }

    @Nested
    inner class DeleteServer {

        @Test
        fun `should delete server as admin`() = runTest {
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
        fun `should return 404 when deleting nonexistent server`() = runTest {
            val response = controller.deleteServer("ghost", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "Expected 404 for nonexistent server delete"
            }
        }

        @Test
        fun `should reject non-admin delete`() = runTest {
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
        fun `connect should return 503 when server has no valid config`() = runTest {
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
        fun `connect should return 404 for unknown server`() = runTest {
            val response = controller.connectServer("nonexistent", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "Expected 404 for unknown server"
            }
        }

        @Test
        fun `disconnect should return 404 for unknown server`() = runTest {
            val response = controller.disconnectServer("nonexistent", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "Expected 404 for unknown server"
            }
        }

        @Test
        fun `disconnect should succeed for registered server`() = runTest {
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
        fun `register then update then delete lifecycle`() = runTest {
            val exchange = adminExchange()

            // 1. Register
            val registerReq = RegisterMcpServerRequest(
                name = "lifecycle-server",
                description = "Initial",
                transportType = "SSE",
                config = mapOf("url" to "http://localhost:8081/sse"),
                autoConnect = false
            )
            val created = controller.registerServer(registerReq, exchange)
            assertEquals(HttpStatus.CREATED, created.statusCode) { "Step 1: Register should succeed" }

            // 2. Verify in list
            val listResponse = controller.listServers(exchange)
            assertEquals(HttpStatus.OK, listResponse.statusCode) { "Step 2: list should return 200" }
            @Suppress("UNCHECKED_CAST")
            val list = listResponse.body as List<McpServerResponse>
            assertEquals(1, list.size) { "Step 2: Should have 1 server" }
            assertEquals("lifecycle-server", list[0].name)

            // 3. Get detail
            val detail = controller.getServer("lifecycle-server", exchange)
            assertEquals(HttpStatus.OK, detail.statusCode) { "Step 3: Get should succeed" }
            assertEquals("Initial", (detail.body as McpServerDetailResponse).description)

            // 4. Update
            val updated = controller.updateServer(
                "lifecycle-server",
                UpdateMcpServerRequest(description = "Updated"),
                exchange
            )
            assertEquals(HttpStatus.OK, updated.statusCode) { "Step 4: Update should succeed" }

            // 5. Verify update
            val afterUpdate = controller.getServer("lifecycle-server", exchange)
            assertEquals("Updated", (afterUpdate.body as McpServerDetailResponse).description) {
                "Step 5: Description should be updated"
            }

            // 6. Delete
            val deleted = controller.deleteServer("lifecycle-server", exchange)
            assertEquals(HttpStatus.NO_CONTENT, deleted.statusCode) { "Step 6: Delete should succeed" }

            // 7. Verify gone
            val afterDeleteListResponse = controller.listServers(exchange)
            @Suppress("UNCHECKED_CAST")
            val afterDeleteList = afterDeleteListResponse.body as List<McpServerResponse>
            assertTrue(afterDeleteList.isEmpty()) { "Step 7: List should be empty after delete" }
        }

        @Test
        fun `security config should filter servers through allowlist`() = runTest {
            val secureStore = InMemoryMcpServerStore()
            val secureManager = DefaultMcpManager(
                securityConfig = McpSecurityConfig(allowedServerNames = setOf("trusted")),
                store = secureStore
            )
            val secureController = McpServerController(secureManager, secureStore, InMemoryAdminAuditStore())

            // Register allowed server
            val trustedReq = RegisterMcpServerRequest(
                name = "trusted",
                transportType = "SSE",
                autoConnect = false
            )
            val trustedResp = secureController.registerServer(trustedReq, adminExchange())
            assertEquals(HttpStatus.CREATED, trustedResp.statusCode) {
                "Trusted server should register"
            }

            // List should have only trusted
            val listResponse = secureController.listServers(adminExchange())
            @Suppress("UNCHECKED_CAST")
            val list = listResponse.body as List<McpServerResponse>
            assertEquals(1, list.size) { "Only trusted server should be listed" }
            assertEquals("trusted", list[0].name)
        }

        @Test
        fun `register should return bad request when server is blocked by allowlist`() = runTest {
            val secureStore = InMemoryMcpServerStore()
            val secureManager = DefaultMcpManager(
                securityConfig = McpSecurityConfig(allowedServerNames = setOf("trusted")),
                store = secureStore
            )
            val secureController = McpServerController(secureManager, secureStore, InMemoryAdminAuditStore())

            val blockedReq = RegisterMcpServerRequest(
                name = "untrusted",
                transportType = "SSE",
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
        fun `register should persist when runtime manager and store are decoupled`() = runTest {
            val runtimeManager = DefaultMcpManager()
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(runtimeManager, persistentStore, InMemoryAdminAuditStore())

            val request = RegisterMcpServerRequest(
                name = "decoupled-register",
                transportType = "SSE",
                config = mapOf("url" to "http://localhost:8081/sse"),
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
