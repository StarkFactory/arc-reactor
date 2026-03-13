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

class McpSwaggerCatalogControllerTest {

    private lateinit var store: McpServerStore
    private lateinit var auditStore: InMemoryAdminAuditStore
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var controller: McpSwaggerCatalogController

    @BeforeEach
    fun setup() {
        store = InMemoryMcpServerStore()
        auditStore = InMemoryAdminAuditStore()
        meterRegistry = SimpleMeterRegistry()
        controller = McpSwaggerCatalogController(store, auditStore, meterRegistry)
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
    fun `listSources should reject non-admin`() = runTest {
        val response = controller.listSources(name = "swagger", exchange = userExchange())

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        val body = response.body as ErrorResponse
        assertEquals("Admin access required", body.error)
        assertTrue(auditStore.list().isEmpty(), "No audit entry should be recorded for rejected non-admin request")
    }

    @Test
    fun `listSources should proxy upstream response and record audit`() = runTest {
        var capturedActor: String? = null
        var capturedRequestId: String? = null

        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/admin/spec-sources") { exchange ->
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
            val payload = """[{"name":"payments","enabled":true}]""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()

        try {
            saveServer(
                name = "swagger",
                config = mapOf(
                    "url" to "http://localhost:${server.address.port}/sse",
                    "adminToken" to "admin-secret"
                )
            )

            val response = controller.listSources(name = "swagger", exchange = adminExchange("catalog-admin"))

            assertEquals(HttpStatus.OK, response.statusCode)
            val body = response.body as List<*>
            assertEquals(1, body.size)
            assertEquals("catalog-admin", capturedActor)
            assertNotNull(capturedRequestId, "Proxy request should include X-Request-Id header")
            val audits = auditStore.list()
            assertEquals(1, audits.size)
            assertEquals("mcp_swagger_catalog", audits.first().category)
            assertEquals("LIST_SOURCES", audits.first().action)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `getSource should proxy source details and record audit`() = runTest {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/admin/spec-sources/payments") { exchange ->
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
            val payload = """
                {
                  "id":"src-1",
                  "name":"payments",
                  "url":"https://example.com/payments/openapi.json",
                  "enabled":true,
                  "syncCron":"0 0 * * * *",
                  "jiraProjectKey":"PAY",
                  "confluenceSpaceKey":"PAYMENTS",
                  "bitbucketRepository":"team/payments",
                  "serviceSlug":"payments-api",
                  "ownerTeam":"payments-platform"
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()

        try {
            saveServer(
                name = "swagger",
                config = mapOf(
                    "url" to "http://localhost:${server.address.port}/sse",
                    "adminToken" to "admin-secret"
                )
            )

            val response = controller.getSource(
                name = "swagger",
                sourceName = "payments",
                exchange = adminExchange("catalog-admin")
            )

            assertEquals(HttpStatus.OK, response.statusCode)
            val body = response.body as Map<*, *>
            assertEquals("payments", body["name"])
            assertEquals("PAY", body["jiraProjectKey"])
            assertEquals("payments-platform", body["ownerTeam"])
            val audits = auditStore.list()
            assertEquals(1, audits.size)
            assertEquals("GET_SOURCE", audits.first().action)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `createSource should forward metadata fields in request body`() = runTest {
        var capturedBody: String? = null

        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/admin/spec-sources") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            if (exchange.requestHeaders.getFirst("X-Admin-Token") != "admin-secret") {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            capturedBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val payload = """{"name":"payments","serviceSlug":"payments-api","ownerTeam":"payments-platform"}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(201, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()

        try {
            saveServer(
                name = "swagger",
                config = mapOf(
                    "url" to "http://localhost:${server.address.port}/sse",
                    "adminToken" to "admin-secret"
                )
            )

            val response = controller.createSource(
                name = "swagger",
                request = SwaggerSpecSourceRequest(
                    name = "payments",
                    url = "https://example.com/payments/openapi.json",
                    enabled = true,
                    syncCron = "0 0 * * * *",
                    jiraProjectKey = "PAY",
                    confluenceSpaceKey = "PAYMENTS",
                    bitbucketRepository = "team/payments",
                    serviceSlug = "payments-api",
                    ownerTeam = "payments-platform"
                ),
                exchange = adminExchange("catalog-admin")
            )

            assertEquals(HttpStatus.CREATED, response.statusCode)
            assertTrue(
                capturedBody.orEmpty().contains("\"jiraProjectKey\":\"PAY\""),
                "Request body should forward jiraProjectKey"
            )
            assertTrue(
                capturedBody.orEmpty().contains("\"confluenceSpaceKey\":\"PAYMENTS\""),
                "Request body should forward confluenceSpaceKey"
            )
            assertTrue(
                capturedBody.orEmpty().contains("\"bitbucketRepository\":\"team/payments\""),
                "Request body should forward bitbucketRepository"
            )
            assertTrue(
                capturedBody.orEmpty().contains("\"serviceSlug\":\"payments-api\""),
                "Request body should forward serviceSlug"
            )
            assertTrue(
                capturedBody.orEmpty().contains("\"ownerTeam\":\"payments-platform\""),
                "Request body should forward ownerTeam"
            )
            val audits = auditStore.list()
            assertEquals(1, audits.size)
            assertEquals("CREATE_SOURCE", audits.first().action)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `listRevisions should forward limit query param`() = runTest {
        var capturedQuery: String? = null

        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/admin/spec-sources/payments/revisions") { exchange ->
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
            capturedQuery = exchange.requestURI.rawQuery
            val payload = """[{"id":"rev-2","sourceId":"src-1","reviewStatus":"READY"}]"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()

        try {
            saveServer(
                name = "swagger",
                config = mapOf(
                    "url" to "http://localhost:${server.address.port}/sse",
                    "adminToken" to "admin-secret"
                )
            )

            val response = controller.listRevisions(
                name = "swagger",
                sourceName = "payments",
                limit = 5,
                exchange = adminExchange("catalog-admin")
            )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("limit=5", capturedQuery)
            val audits = auditStore.list()
            assertEquals(1, audits.size)
            assertEquals("LIST_REVISIONS", audits.first().action)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `getDiff should forward encoded query params`() = runTest {
        var capturedQuery: String? = null

        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/admin/spec-sources/payments/diff") { exchange ->
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
            capturedQuery = exchange.requestURI.rawQuery
            val payload = """{"endpointsAdded":["POST /v2/payments"]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()

        try {
            saveServer(
                name = "swagger",
                config = mapOf(
                    "url" to "http://localhost:${server.address.port}/sse",
                    "adminToken" to "admin-secret"
                )
            )

            val response = controller.getDiff(
                name = "swagger",
                sourceName = "payments",
                from = "rev 1",
                to = "rev/2",
                exchange = adminExchange("catalog-admin")
            )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("from=rev%201&to=rev/2", capturedQuery)
            assertTrue(
                capturedQuery.orEmpty().contains("%2520").not(),
                "Query params should not be double-encoded"
            )
            val audits = auditStore.list()
            assertEquals(1, audits.size)
            assertEquals("GET_DIFF", audits.first().action)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `publishRevision should forward hmac headers and request body`() = runTest {
        val secret = "swagger-hmac-secret"
        var capturedBody: String? = null

        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/admin/spec-sources/payments/publish") { exchange ->
            if (exchange.requestMethod != "POST") {
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
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            capturedBody = body
            val expectedSignature = sign(
                secret = secret,
                method = "POST",
                path = "/admin/spec-sources/payments/publish",
                query = "",
                body = body,
                timestamp = timestamp.orEmpty()
            )
            if (timestamp.isNullOrBlank() || signature != expectedSignature) {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }

            val payload = """{"sourceName":"payments","revisionId":"rev-2"}""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()

        try {
            saveServer(
                name = "swagger",
                config = mapOf(
                    "url" to "http://localhost:${server.address.port}/sse",
                    "adminToken" to "admin-secret",
                    "adminHmacSecret" to secret,
                    "adminHmacRequired" to true
                )
            )

            val response = controller.publishRevision(
                name = "swagger",
                sourceName = "payments",
                request = SwaggerPublishRevisionRequest(revisionId = "rev-2"),
                exchange = adminExchange("release-admin")
            )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertTrue(
                capturedBody.orEmpty().contains("\"revisionId\":\"rev-2\""),
                "Publish request body should contain the revisionId"
            )
            val audits = auditStore.list()
            assertEquals(1, audits.size)
            assertEquals("PUBLISH_REVISION", audits.first().action)
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
        val canonical = listOf(
            method.uppercase(),
            path,
            query,
            timestamp,
            sha256(body)
        ).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
