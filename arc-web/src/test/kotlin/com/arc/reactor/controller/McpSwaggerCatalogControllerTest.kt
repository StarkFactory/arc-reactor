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
 * McpSwaggerCatalogController에 대한 테스트.
 *
 * MCP Swagger 카탈로그 REST API의 동작을 검증합니다.
 */
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
    fun `listSources은(는) reject non-admin해야 한다`() = runTest {
        val response = controller.listSources(name = "swagger", exchange = userExchange())

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "비관리자 요청은 403이어야 한다" }
        val body = response.body as ErrorResponse
        assertEquals("Admin access required", body.error) { "에러 메시지가 일치해야 한다" }
        assertTrue(auditStore.list().isEmpty()) {
            "비관리자 요청 거부 시 감사 항목이 기록되지 않아야 한다"
        }
    }

    @Test
    fun `listSources은(는) proxy upstream response and record audit해야 한다`() = runTest {
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

            assertEquals(HttpStatus.OK, response.statusCode) { "프록시 응답이 200이어야 한다" }
            val body = response.body as List<*>
            assertEquals(1, body.size) { "소스 목록이 1개여야 한다" }
            assertEquals("catalog-admin", capturedActor) { "X-Admin-Actor 헤더가 전달되어야 한다" }
            assertNotNull(capturedRequestId) { "프록시 요청에 X-Request-Id 헤더가 포함되어야 한다" }
            val audits = auditStore.list()
            assertEquals(1, audits.size) { "감사 로그가 1건 기록되어야 한다" }
            assertEquals("mcp_swagger_catalog", audits.first().category) {
                "감사 카테고리가 mcp_swagger_catalog여야 한다"
            }
            assertEquals("LIST_SOURCES", audits.first().action) { "감사 액션이 LIST_SOURCES여야 한다" }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `getSource은(는) proxy source details and record audit해야 한다`() = runTest {
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

            assertEquals(HttpStatus.OK, response.statusCode) { "소스 조회 응답이 200이어야 한다" }
            val body = response.body as Map<*, *>
            assertEquals("payments", body["name"]) { "소스 이름이 payments여야 한다" }
            assertEquals("PAY", body["jiraProjectKey"]) { "jiraProjectKey가 PAY여야 한다" }
            assertEquals("payments-platform", body["ownerTeam"]) { "ownerTeam이 payments-platform이어야 한다" }
            val audits = auditStore.list()
            assertEquals(1, audits.size) { "감사 로그가 1건 기록되어야 한다" }
            assertEquals("GET_SOURCE", audits.first().action) { "감사 액션이 GET_SOURCE여야 한다" }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `createSource은(는) forward metadata fields in request body해야 한다`() = runTest {
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

            assertEquals(HttpStatus.CREATED, response.statusCode) { "소스 생성 응답이 201이어야 한다" }
            assertTrue(
                capturedBody.orEmpty().contains("\"jiraProjectKey\":\"PAY\"")
            ) { "요청 바디에 jiraProjectKey가 전달되어야 한다" }
            assertTrue(
                capturedBody.orEmpty().contains("\"confluenceSpaceKey\":\"PAYMENTS\"")
            ) { "요청 바디에 confluenceSpaceKey가 전달되어야 한다" }
            assertTrue(
                capturedBody.orEmpty().contains("\"bitbucketRepository\":\"team/payments\"")
            ) { "요청 바디에 bitbucketRepository가 전달되어야 한다" }
            assertTrue(
                capturedBody.orEmpty().contains("\"serviceSlug\":\"payments-api\"")
            ) { "요청 바디에 serviceSlug가 전달되어야 한다" }
            assertTrue(
                capturedBody.orEmpty().contains("\"ownerTeam\":\"payments-platform\"")
            ) { "요청 바디에 ownerTeam이 전달되어야 한다" }
            val audits = auditStore.list()
            assertEquals(1, audits.size) { "감사 로그가 1건 기록되어야 한다" }
            assertEquals("CREATE_SOURCE", audits.first().action) { "감사 액션이 CREATE_SOURCE여야 한다" }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `listRevisions은(는) forward limit query param해야 한다`() = runTest {
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

            assertEquals(HttpStatus.OK, response.statusCode) { "리비전 목록 응답이 200이어야 한다" }
            assertEquals("limit=5", capturedQuery) { "limit 쿼리 파라미터가 전달되어야 한다" }
            val audits = auditStore.list()
            assertEquals(1, audits.size) { "감사 로그가 1건 기록되어야 한다" }
            assertEquals("LIST_REVISIONS", audits.first().action) { "감사 액션이 LIST_REVISIONS여야 한다" }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `getDiff은(는) forward encoded query params해야 한다`() = runTest {
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

            assertEquals(HttpStatus.OK, response.statusCode) { "diff 조회 응답이 200이어야 한다" }
            assertEquals("from=rev%201&to=rev/2", capturedQuery) {
                "쿼리 파라미터가 올바르게 인코딩되어야 한다"
            }
            assertTrue(
                capturedQuery.orEmpty().contains("%2520").not()
            ) { "쿼리 파라미터가 이중 인코딩되지 않아야 한다" }
            val audits = auditStore.list()
            assertEquals(1, audits.size) { "감사 로그가 1건 기록되어야 한다" }
            assertEquals("GET_DIFF", audits.first().action) { "감사 액션이 GET_DIFF여야 한다" }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `publishRevision은(는) forward hmac headers and request body해야 한다`() = runTest {
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

            assertEquals(HttpStatus.OK, response.statusCode) { "리비전 게시 응답이 200이어야 한다" }
            assertTrue(
                capturedBody.orEmpty().contains("\"revisionId\":\"rev-2\"")
            ) { "게시 요청 바디에 revisionId가 포함되어야 한다" }
            val audits = auditStore.list()
            assertEquals(1, audits.size) { "감사 로그가 1건 기록되어야 한다" }
            assertEquals("PUBLISH_REVISION", audits.first().action) {
                "감사 액션이 PUBLISH_REVISION이어야 한다"
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
