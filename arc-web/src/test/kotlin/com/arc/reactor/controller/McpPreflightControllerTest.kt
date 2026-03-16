package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.mcp.InMemoryMcpServerStore
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.server.ServerWebExchange
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * McpPreflightController에 대한 테스트.
 *
 * MCP 사전 검증 REST API의 동작을 검증합니다.
 */
class McpPreflightControllerTest {

    private lateinit var store: McpServerStore
    private lateinit var auditStore: InMemoryAdminAuditStore
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var controller: McpPreflightController

    @BeforeEach
    fun setup() {
        store = InMemoryMcpServerStore()
        auditStore = InMemoryAdminAuditStore()
        meterRegistry = SimpleMeterRegistry()
        controller = McpPreflightController(store, auditStore, meterRegistry)
    }

    private fun adminExchange(
        userId: String = "admin-user",
        headers: HttpHeaders = HttpHeaders()
    ): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>(relaxed = true)
        val request = mockk<ServerHttpRequest>(relaxed = true)
        val attrs = mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN,
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to userId
        )
        every { request.headers } returns headers
        every { exchange.request } returns request
        every { exchange.attributes } returns attrs
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>(relaxed = true)
        val request = mockk<ServerHttpRequest>(relaxed = true)
        val attrs = mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER,
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to "regular-user"
        )
        every { request.headers } returns HttpHeaders()
        every { exchange.request } returns request
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

    @Test
    fun `getPreflight은(는) reject non-admin해야 한다`() = runTest {
        val response = controller.getPreflight(name = "atlassian", exchange = userExchange())

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "Expected 403 FORBIDDEN when non-admin reads MCP preflight"
        }
        val body = response.body as ErrorResponse
        assertEquals("Admin access required", body.error) {
            "Forbidden response should use the standard admin message"
        }
        assertTrue(auditStore.list().isEmpty()) {
            "Forbidden preflight requests must not create audit entries"
        }
    }

    @Test
    fun `getPreflight은(는) proxy upstream response and record audit해야 한다`() = runTest {
        var capturedActor: String? = null
        var capturedRequestId: String? = null

        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/admin/preflight") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            if (exchange.requestHeaders.getFirst("X-Admin-Token") != "admin-secret") {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }

            capturedActor = exchange.requestHeaders.getFirst("X-Admin-Actor")
            capturedRequestId = exchange.requestHeaders.getFirst("X-Request-Id")
            val payload = """
                {"ok":true,"readyForProduction":false,"policySource":"dynamic","summary":{"passCount":7,"warnCount":1,"failCount":1},"checks":[{"name":"admin_hmac","status":"WARN","message":"disabled"},{"name":"bitbucket_connectivity","status":"FAIL","message":"Resource not found."}]}
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()

        try {
            val port = server.address.port
            saveServer(
                name = "atlassian",
                config = mapOf(
                    "url" to "http://localhost:$port/sse",
                    "adminToken" to "admin-secret"
                )
            )

            val response = controller.getPreflight(name = "atlassian", exchange = adminExchange("ops-admin"))

            assertEquals(HttpStatus.OK, response.statusCode) {
                "Expected successful preflight proxy response"
            }
            val body = response.body as Map<*, *>
            assertEquals(true, body["ok"]) {
                "Expected upstream ok flag to be preserved"
            }
            assertEquals("dynamic", body["policySource"]) {
                "Expected policy source from upstream preflight"
            }
            assertEquals("ops-admin", capturedActor) {
                "Expected current admin actor to be forwarded"
            }
            assertNotNull(capturedRequestId) {
                "Expected a request ID to be forwarded for traceability"
            }

            val audits = auditStore.list()
            assertEquals(1, audits.size) {
                "Expected one audit record for preflight read"
            }
            assertEquals("mcp_preflight", audits.first().category) {
                "Unexpected audit category for preflight"
            }
            assertEquals("READ", audits.first().action) {
                "Unexpected audit action for preflight"
            }
            val detail = audits.first().detail.orEmpty()
            assertTrue(detail.contains("status=200")) {
                "Audit detail should include the proxied HTTP status"
            }
            assertTrue(detail.contains("policySource=dynamic")) {
                "Audit detail should preserve the upstream policy source"
            }
            assertTrue(detail.contains("ok=true")) {
                "Audit detail should preserve upstream preflight success state"
            }
            assertTrue(detail.contains("readyForProduction=false")) {
                "Audit detail should preserve production-readiness state"
            }
            assertTrue(detail.contains("passCount=7")) {
                "Audit detail should include summarized pass count"
            }
            assertTrue(detail.contains("warnCount=1")) {
                "Audit detail should include summarized warn count"
            }
            assertTrue(detail.contains("failCount=1")) {
                "Audit detail should include summarized fail count"
            }
            assertTrue(detail.contains("admin_hmac:WARN:disabled")) {
                "Audit detail should capture warning checks for operator diagnosis"
            }
            assertTrue(detail.contains("bitbucket_connectivity:FAIL:Resource not found.")) {
                "Audit detail should capture failing Bitbucket checks for operator diagnosis"
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `getPreflight은(는) forward hmac headers when configured해야 한다`() = runTest {
        val secret = "preflight-hmac-secret"
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/admin/preflight") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            if (exchange.requestHeaders.getFirst("X-Admin-Token") != "admin-secret") {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }

            val timestamp = exchange.requestHeaders.getFirst("X-Admin-Timestamp")
            val signature = exchange.requestHeaders.getFirst("X-Admin-Signature")
            val expectedSignature = sign(
                secret = secret,
                method = "GET",
                path = "/admin/preflight",
                query = "",
                body = "",
                timestamp = timestamp.orEmpty()
            )
            if (timestamp.isNullOrBlank() || signature != expectedSignature) {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }

            val payload = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()

        try {
            val port = server.address.port
            saveServer(
                name = "secured-atlassian",
                config = mapOf(
                    "url" to "http://localhost:$port/sse",
                    "adminToken" to "admin-secret",
                    "adminHmacSecret" to secret,
                    "adminHmacRequired" to true
                )
            )
            val exchange = adminExchange(
                userId = "security-admin",
                headers = HttpHeaders().apply { set("X-Request-Id", "preflight-req-1") }
            )

            val response = controller.getPreflight(name = "secured-atlassian", exchange = exchange)

            assertEquals(HttpStatus.OK, response.statusCode) {
                "Expected successful preflight proxy response with valid HMAC"
            }
        } finally {
            server.stop(0)
        }
    }

    private fun sign(
        secret: String,
        method: String,
        path: String,
        query: String,
        body: String,
        timestamp: String
    ): String {
        val bodyHash = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val canonical = listOf(method, path, query, timestamp, bodyHash).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
