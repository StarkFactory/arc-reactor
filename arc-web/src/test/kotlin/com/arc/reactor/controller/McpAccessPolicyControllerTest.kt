package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.mcp.InMemoryMcpServerStore
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class McpAccessPolicyControllerTest {

    private lateinit var store: McpServerStore
    private lateinit var auditStore: InMemoryAdminAuditStore
    private lateinit var controller: McpAccessPolicyController

    @BeforeEach
    fun setup() {
        store = InMemoryMcpServerStore()
        auditStore = InMemoryAdminAuditStore()
        controller = McpAccessPolicyController(store, auditStore)
    }

    private fun adminExchange(userId: String = "admin-user"): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>(relaxed = true)
        val attrs = mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN,
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to userId
        )
        every { exchange.attributes } returns attrs
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>(relaxed = true)
        val attrs = mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER,
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to "regular-user"
        )
        every { exchange.attributes } returns attrs
        return exchange
    }

    private fun saveServer(name: String, config: Map<String, Any>) {
        store.save(
            McpServer(
                name = name,
                transportType = McpTransportType.SSE,
                config = config,
                autoConnect = false
            )
        )
    }

    @Nested
    inner class Authorization {

        @Test
        fun `getPolicy should reject non-admin`() = runTest {
            val response = controller.getPolicy(name = "any", exchange = userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Expected 403 FORBIDDEN for non-admin access"
            }
            val body = response.body as ErrorResponse
            assertEquals("Admin access required", body.error) {
                "Forbidden response should use standardized admin error message"
            }
            assertTrue(auditStore.list().isEmpty()) { "Forbidden requests should not create admin audit entries" }
        }
    }

    @Nested
    inner class ValidationAndAudit {

        @Test
        fun `getPolicy should return not found and record read audit`() = runTest {
            val response = controller.getPolicy(name = "missing-server", exchange = adminExchange("ops-admin"))

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "Expected 404 NOT_FOUND for unknown MCP server"
            }
            val body = response.body as ErrorResponse
            assertTrue(body.error.contains("not found")) {
                "Expected not-found message in error response, got: ${body.error}"
            }

            val audits = auditStore.list()
            assertEquals(1, audits.size) { "Expected one READ audit log entry for getPolicy request" }
            assertEquals("mcp_access_policy", audits.first().category) { "Unexpected audit category" }
            assertEquals("READ", audits.first().action) { "Unexpected audit action" }
            assertEquals("ops-admin", audits.first().actor) { "Audit actor should be derived from exchange user id" }
            assertTrue(audits.first().detail.orEmpty().contains("status=404")) {
                "Audit detail should include upstream status code 404"
            }
        }

        @Test
        fun `getPolicy should reject invalid admin url config`() = runTest {
            saveServer(
                name = "invalid-admin-url",
                config = mapOf(
                    "adminUrl" to "ftp://internal-admin/policy",
                    "adminToken" to "secret-token"
                )
            )

            val response = controller.getPolicy(name = "invalid-admin-url", exchange = adminExchange())

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "Expected 400 BAD_REQUEST for invalid admin URL configuration"
            }
            val body = response.body as ErrorResponse
            assertTrue(body.error.contains("invalid admin URL")) {
                "Expected invalid admin URL error message, got: ${body.error}"
            }
        }

        @Test
        fun `getPolicy should reject when admin token is missing`() = runTest {
            saveServer(
                name = "missing-admin-token",
                config = mapOf("url" to "https://mcp.example.com/sse")
            )

            val response = controller.getPolicy(name = "missing-admin-token", exchange = adminExchange())

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "Expected 400 BAD_REQUEST when admin token is absent"
            }
            val body = response.body as ErrorResponse
            assertTrue(body.error.contains("admin token")) {
                "Expected missing token message, got: ${body.error}"
            }
        }

        @Test
        fun `updatePolicy should include project and space counts in audit detail`() = runTest {
            saveServer(
                name = "update-audit-target",
                config = mapOf(
                    "adminUrl" to "ftp://invalid-admin-host/policy",
                    "adminToken" to "secret-token"
                )
            )

            val request = UpdateMcpAccessPolicyRequest(
                allowedJiraProjectKeys = listOf("CORE", "OPS"),
                allowedConfluenceSpaceKeys = listOf("PLATFORM")
            )

            val response = controller.updatePolicy(
                name = "update-audit-target",
                request = request,
                exchange = adminExchange("security-admin")
            )

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "Expected 400 BAD_REQUEST for invalid admin URL during update"
            }

            val audits = auditStore.list()
            assertEquals(1, audits.size) { "Expected one UPDATE audit entry for updatePolicy call" }
            assertEquals("UPDATE", audits.first().action) { "Unexpected audit action for updatePolicy" }
            assertEquals("security-admin", audits.first().actor) { "Unexpected actor in audit log" }
            assertTrue(audits.first().detail.orEmpty().contains("status=400")) {
                "Audit detail should include upstream status code for failed update"
            }
            assertTrue(audits.first().detail.orEmpty().contains("jiraProjects=2")) {
                "Audit detail should include Jira project count"
            }
            assertTrue(audits.first().detail.orEmpty().contains("confluenceSpaces=1")) {
                "Audit detail should include Confluence space count"
            }
        }
    }

    @Nested
    inner class ProxyStatusHandling {

        @Test
        fun `clearPolicy should preserve upstream no-content status`() = runTest {
            val server = HttpServer.create(InetSocketAddress(0), 0)
            server.createContext("/admin/access-policy") { exchange ->
                if (exchange.requestMethod != "DELETE") {
                    exchange.sendResponseHeaders(405, -1)
                    exchange.close()
                    return@createContext
                }

                val token = exchange.requestHeaders.getFirst("X-Admin-Token")
                if (token != "admin-secret") {
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                    return@createContext
                }

                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            server.start()

            try {
                val port = server.address.port
                saveServer(
                    name = "no-content-server",
                    config = mapOf(
                        "url" to "http://localhost:$port/sse",
                        "adminToken" to "admin-secret"
                    )
                )

                val response = controller.clearPolicy(name = "no-content-server", exchange = adminExchange())

                assertEquals(HttpStatus.NO_CONTENT, response.statusCode) {
                    "Proxy should preserve upstream 204 NO_CONTENT instead of converting to 200"
                }
            } finally {
                server.stop(0)
            }
        }

        @Test
        fun `getPolicy should return gateway timeout when upstream is too slow`() = runTest {
            val server = HttpServer.create(InetSocketAddress(0), 0)
            server.createContext("/admin/access-policy") { exchange ->
                if (exchange.requestMethod != "GET") {
                    exchange.sendResponseHeaders(405, -1)
                    exchange.close()
                    return@createContext
                }

                Thread.sleep(300)
                val payload = """{"status":"ok"}""".toByteArray()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            server.start()

            try {
                val port = server.address.port
                saveServer(
                    name = "slow-server",
                    config = mapOf(
                        "url" to "http://localhost:$port/sse",
                        "adminToken" to "admin-secret",
                        "adminTimeoutMs" to 50
                    )
                )

                val response = controller.getPolicy(name = "slow-server", exchange = adminExchange("timeout-admin"))

                assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.statusCode) {
                    "Expected 504 GATEWAY_TIMEOUT when upstream exceeds configured timeout"
                }
                val body = response.body as ErrorResponse
                assertTrue(body.error.contains("timed out")) {
                    "Expected timeout error message, got: ${body.error}"
                }

                val audits = auditStore.list()
                assertEquals(1, audits.size) { "Expected one READ audit log entry for timed-out request" }
                assertTrue(audits.first().detail.orEmpty().contains("status=504")) {
                    "Audit detail should record 504 status for timeout case"
                }
            } finally {
                server.stop(0)
            }
        }
    }
}
