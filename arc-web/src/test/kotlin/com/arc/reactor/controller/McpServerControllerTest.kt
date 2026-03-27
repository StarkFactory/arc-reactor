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
        controller = McpServerController(
            manager, store, adminAuditStore, com.arc.reactor.agent.config.AgentProperties()
        )
    }

    @Nested
    inner class ListServers {

        @Test
        fun `no servers registered일 때 return empty list해야 한다`() {
            val response = controller.listServers(adminExchange())
            assertEquals(HttpStatus.OK, response.statusCode) { "관리자 목록 조회는 200이어야 한다" }
            @Suppress("UNCHECKED_CAST")
            val result = response.body as List<McpServerResponse>
            assertTrue(result.isEmpty()) {
                "초기 상태에서 서버 목록이 비어있어야 한다. 실제 서버 수: ${result.size}"
            }
        }

        @Test
        fun `status로 list registered servers해야 한다`() {
            manager.register(McpServer(
                name = "test-sse",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse")
            ))

            val response = controller.listServers(adminExchange())
            assertEquals(HttpStatus.OK, response.statusCode) { "관리자 목록 조회는 200이어야 한다" }
            @Suppress("UNCHECKED_CAST")
            val result = response.body as List<McpServerResponse>
            assertEquals(1, result.size) { "서버가 1개 조회되어야 한다" }
            assertEquals("test-sse", result[0].name) { "등록된 서버 이름이 test-sse여야 한다" }
            assertEquals("SSE", result[0].transportType) { "서버 전송 타입이 SSE여야 한다" }
            assertEquals("PENDING", result[0].status) { "서버 상태가 PENDING이어야 한다" }
        }

        @Test
        fun `reject non-admin list해야 한다`() {
            val response = controller.listServers(userExchange())
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "비관리자의 서버 목록 조회는 403이어야 한다"
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
                "서버 등록은 201이어야 한다. 실제 상태: ${response.statusCode}"
            }

            // persisted in store 확인
            val saved = store.findByName("my-server")
            assertNotNull(saved) { "등록된 서버가 스토어에 저장되어야 한다" }
            assertEquals(McpTransportType.SSE, saved!!.transportType) {
                "저장된 서버의 전송 타입이 SSE여야 한다"
            }
            val audits = adminAuditStore.list()
            assertEquals(1, audits.size) { "감사 로그가 1건 기록되어야 한다" }
            assertEquals("mcp_server", audits.first().category) { "감사 카테고리가 mcp_server여야 한다" }
            assertEquals("CREATE", audits.first().action) { "감사 액션이 CREATE여야 한다" }
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
                "중복 이름 등록은 409이어야 한다"
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
                "비관리자의 서버 등록은 403이어야 한다"
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
                "유효하지 않은 전송 타입 등록은 400이어야 한다"
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
                "HTTP 전송 타입은 지원되지 않으므로 400이어야 한다"
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
                "앞뒤 공백이 있는 전송 타입도 정상 파싱되어 201이어야 한다"
            }

            val saved = store.findByName("trimmed-transport")
            assertNotNull(saved) {
                "공백 포함 전송 타입으로 등록된 서버가 스토어에 저장되어야 한다"
            }
            assertEquals(McpTransportType.SSE, saved!!.transportType) {
                "공백 제거 및 대소문자 무시 후 전송 타입이 SSE로 해석되어야 한다"
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
                "실제 STDIO 서버 등록 요청이 성공하여 201이어야 한다"
            }

            val saved = store.findByName("filesystem-prod")
            assertNotNull(saved) { "등록된 STDIO 서버가 스토어에 저장되어야 한다" }
            assertEquals(McpTransportType.STDIO, saved!!.transportType) {
                "저장된 서버의 전송 타입이 STDIO여야 한다"
            }
            assertEquals("npx", saved.config["command"]) {
                "저장된 STDIO 서버의 command 설정값이 npx여야 한다"
            }
            assertEquals(
                listOf("-y", "@modelcontextprotocol/server-filesystem", "/var/data"),
                saved.config["args"]
            ) {
                "저장된 STDIO 서버의 args 설정값이 원본과 동일해야 한다"
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
                "사설 IP를 가리키는 SSE URL은 400으로 거부되어야 한다"
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
                "클라우드 메타데이터 링크로컬 IP를 가리키는 SSE URL은 400으로 거부되어야 한다"
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
                "localhost를 가리키는 SSE URL은 400으로 거부되어야 한다"
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
                "file:// 스킴을 사용하는 SSE URL은 400으로 거부되어야 한다"
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
                "링크로컬 사설 IP를 가리키는 SSE adminUrl은 400으로 거부되어야 한다"
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
                "STDIO 전송 타입은 SSRF 검증 대상이 아니므로 201이어야 한다"
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
                "192.168.x.x 사설 대역을 가리키는 SSE URL은 400으로 거부되어야 한다"
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
                "172.16.x.x 사설 대역을 가리키는 SSE URL은 400으로 거부되어야 한다"
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
                "서버 상세 조회는 200이어야 한다"
            }

            val body = response.body as McpServerDetailResponse
            assertEquals("detail-test", body.name) { "서버 이름이 detail-test여야 한다" }
            assertEquals("Test server", body.description) { "서버 설명이 일치해야 한다" }
            assertEquals("STDIO", body.transportType) { "서버 전송 타입이 STDIO여야 한다" }
            assertEquals("PENDING", body.status) { "서버 상태가 PENDING이어야 한다" }
            assertTrue(body.tools.isEmpty()) { "연결되지 않은 서버는 도구 목록이 비어있어야 한다" }
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
            assertEquals(HttpStatus.OK, response.statusCode) { "마스킹 검증 조회는 200이어야 한다" }

            val body = response.body as McpServerDetailResponse
            assertEquals("http://localhost:8081/sse", body.config["url"]) { "url 설정값이 마스킹되지 않아야 한다" }
            assertEquals("********", body.config["apiKey"]) { "apiKey는 마스킹되어야 한다" }
            assertEquals("********", body.config["adminToken"]) { "adminToken은 마스킹되어야 한다" }
            val headers = body.config["headers"] as Map<*, *>
            assertEquals("********", headers["Authorization"]) { "Authorization 헤더는 마스킹되어야 한다" }
            assertEquals("trace-123", headers["X-Trace-Id"]) { "X-Trace-Id 헤더는 마스킹되지 않아야 한다" }
            val targets = body.config["targets"] as List<*>
            val firstTarget = targets[0] as Map<*, *>
            val secondTarget = targets[1] as Map<*, *>
            assertEquals("********", firstTarget["accessToken"]) { "리스트 내 accessToken은 마스킹되어야 한다" }
            assertEquals("safe-target", secondTarget["name"]) { "리스트 내 안전한 값은 마스킹되지 않아야 한다" }
        }

        @Test
        fun `unknown server에 대해 return 404해야 한다`() {
            val response = controller.getServer("nonexistent", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "존재하지 않는 서버 조회는 404이어야 한다"
            }
        }

        @Test
        fun `reject non-admin get detail해야 한다`() {
            val response = controller.getServer("detail-test", userExchange())
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "비관리자의 서버 상세 조회는 403이어야 한다"
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
                "서버 설정 업데이트는 200이어야 한다"
            }

            val updated = store.findByName("update-me")!!
            assertEquals("Updated description", updated.description) { "업데이트된 서버 설명이 일치해야 한다" }
            assertEquals("http://example.org:9090/sse", updated.config["url"]) { "업데이트된 서버 url이 일치해야 한다" }
        }

        @Test
        fun `updating nonexistent server일 때 return 404해야 한다`() = runTest {
            val response = controller.updateServer(
                "ghost",
                UpdateMcpServerRequest(description = "test"),
                adminExchange()
            )
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "존재하지 않는 서버 업데이트는 404이어야 한다"
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
                "비관리자의 서버 업데이트는 403이어야 한다"
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
                "전송 타입을 생략한 업데이트는 200이어야 한다"
            }

            val updated = store.findByName("preserve-transport")
            assertNotNull(updated) { "업데이트된 서버가 스토어에 존재해야 한다" }
            assertEquals(McpTransportType.STDIO, updated!!.transportType) {
                "전송 타입을 생략하면 기존 전송 타입이 유지되어야 한다"
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
                "유효하지 않은 전송 타입으로 업데이트하면 400이어야 한다"
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
                "HTTP 전송 타입으로 업데이트하면 400이어야 한다"
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
                "공백이 포함된 전송 타입으로 업데이트해도 200이어야 한다"
            }

            val updated = store.findByName("trim-update-transport")
            assertNotNull(updated) { "업데이트 성공 후 서버가 스토어에 존재해야 한다" }
            assertEquals(McpTransportType.STDIO, updated!!.transportType) {
                "공백 제거 후 전송 타입이 STDIO로 업데이트되어야 한다"
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
                "SSRF URL로 업데이트하면 400이어야 한다"
            }
        }

        @Test
        fun `store and manager are decoupled일 때 sync runtime manager state해야 한다`() = runTest {
            val runtimeManager = DefaultMcpManager()
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(
                runtimeManager, persistentStore,
                InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties()
            )

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
                "런타임 매니저와 스토어가 분리된 환경에서도 업데이트는 200이어야 한다"
            }
            val runtimeServer = runtimeManager.listServers().first { it.name == "sync-runtime" }
            assertEquals("new-description", runtimeServer.description) {
                "업데이트 후 런타임 매니저의 서버 상태가 최신 스토어 설정으로 동기화되어야 한다"
            }
        }

        @Test
        fun `connection config changes일 때 reconnect connected server해야 한다`() = runTest {
            val runtimeManager = mockk<McpManager>(relaxed = true)
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(
                runtimeManager, persistentStore,
                InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties()
            )

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

            assertEquals(HttpStatus.OK, response.statusCode) { "설정 변경 업데이트 응답이 200이어야 한다" }
            verify(exactly = 1) { runtimeManager.syncRuntimeServer(any()) }
            coVerify(exactly = 1) { runtimeManager.disconnect("reconnect-me") }
            coVerify(exactly = 1) { runtimeManager.connect("reconnect-me") }
        }

        @Test
        fun `only description changes일 때 not reconnect connected server해야 한다`() = runTest {
            val runtimeManager = mockk<McpManager>(relaxed = true)
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(
                runtimeManager, persistentStore,
                InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties()
            )

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

            assertEquals(HttpStatus.OK, response.statusCode) { "설명만 변경한 업데이트 응답이 200이어야 한다" }
            verify(exactly = 1) { runtimeManager.syncRuntimeServer(any()) }
            coVerify(exactly = 0) { runtimeManager.disconnect(any()) }
            coVerify(exactly = 0) { runtimeManager.connect(any()) }
        }

        @Test
        fun `autoConnect is enabled from disconnected state일 때 connect server after update해야 한다`() = runTest {
            val runtimeManager = mockk<McpManager>(relaxed = true)
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(
                runtimeManager, persistentStore,
                InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties()
            )

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

            assertEquals(HttpStatus.OK, response.statusCode) { "autoConnect 활성화 업데이트 응답이 200이어야 한다" }
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
                "서버 삭제는 204이어야 한다"
            }

            assertNull(store.findByName("delete-me")) { "삭제된 서버가 스토어에서 제거되어야 한다" }
            assertTrue(manager.listServers().isEmpty()) { "삭제된 서버가 매니저에서도 제거되어야 한다" }
        }

        @Test
        fun `deleting nonexistent server일 때 return 404해야 한다`() = runTest {
            val response = controller.deleteServer("ghost", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "존재하지 않는 서버 삭제는 404이어야 한다"
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
                "비관리자의 서버 삭제는 403이어야 한다"
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
                "유효하지 않은 설정으로 연결 시도하면 503이어야 한다"
            }
        }

        @Test
        fun `connect은(는) return 404 for unknown server해야 한다`() = runTest {
            val response = controller.connectServer("nonexistent", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "존재하지 않는 서버 연결 시도는 404이어야 한다"
            }
        }

        @Test
        fun `disconnect은(는) return 404 for unknown server해야 한다`() = runTest {
            val response = controller.disconnectServer("nonexistent", adminExchange())
            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "존재하지 않는 서버 연결 해제 시도는 404이어야 한다"
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
                "등록된 서버의 연결 해제는 200이어야 한다"
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
            assertEquals(HttpStatus.CREATED, created.statusCode) { "Step 1: 서버 등록이 201로 성공해야 한다" }

            // 2. 목록에서 확인
            val listResponse = controller.listServers(exchange)
            assertEquals(HttpStatus.OK, listResponse.statusCode) { "Step 2: 서버 목록 조회는 200이어야 한다" }
            @Suppress("UNCHECKED_CAST")
            val list = listResponse.body as List<McpServerResponse>
            assertEquals(1, list.size) { "Step 2: 서버가 1개 조회되어야 한다" }
            assertEquals("lifecycle-server", list[0].name) { "Step 2: 등록된 서버 이름이 lifecycle-server여야 한다" }

            // 3. 상세 조회
            val detail = controller.getServer("lifecycle-server", exchange)
            assertEquals(HttpStatus.OK, detail.statusCode) { "Step 3: 서버 상세 조회는 200이어야 한다" }
            assertEquals(
                "Initial",
                (detail.body as McpServerDetailResponse).description
            ) { "Step 3: 서버 설명이 Initial이어야 한다" }

            // 4. 업데이트
            val updated = controller.updateServer(
                "lifecycle-server",
                UpdateMcpServerRequest(description = "Updated"),
                exchange
            )
            assertEquals(HttpStatus.OK, updated.statusCode) { "Step 4: 서버 업데이트는 200이어야 한다" }

            // 5. 업데이트 확인
            val afterUpdate = controller.getServer("lifecycle-server", exchange)
            assertEquals("Updated", (afterUpdate.body as McpServerDetailResponse).description) {
                "Step 5: 업데이트 후 서버 설명이 Updated로 변경되어야 한다"
            }

            // 6. 삭제
            val deleted = controller.deleteServer("lifecycle-server", exchange)
            assertEquals(HttpStatus.NO_CONTENT, deleted.statusCode) { "Step 6: 서버 삭제는 204이어야 한다" }

            // 7. 삭제 확인
            val afterDeleteListResponse = controller.listServers(exchange)
            @Suppress("UNCHECKED_CAST")
            val afterDeleteList = afterDeleteListResponse.body as List<McpServerResponse>
            assertTrue(afterDeleteList.isEmpty()) { "Step 7: 삭제 후 서버 목록이 비어있어야 한다" }
        }

        @Test
        fun `security config은(는) filter servers through allowlist해야 한다`() = runTest {
            val secureStore = InMemoryMcpServerStore()
            val secureManager = DefaultMcpManager(
                securityConfig = McpSecurityConfig(allowedServerNames = setOf("trusted")),
                store = secureStore
            )
            val secureController = McpServerController(
                secureManager, secureStore, InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties()
            )

            // allowed server 등록
            val trustedReq = RegisterMcpServerRequest(
                name = "trusted",
                transportType = "SSE",
                config = mapOf("url" to "http://example.com:8081/sse"),
                autoConnect = false
            )
            val trustedResp = secureController.registerServer(trustedReq, adminExchange())
            assertEquals(HttpStatus.CREATED, trustedResp.statusCode) {
                "허용된 서버 등록은 201이어야 한다"
            }

            // List은(는) have only trusted해야 합니다
            val listResponse = secureController.listServers(adminExchange())
            @Suppress("UNCHECKED_CAST")
            val list = listResponse.body as List<McpServerResponse>
            assertEquals(1, list.size) { "허용 목록에 있는 서버만 조회되어야 한다" }
            assertEquals("trusted", list[0].name) { "허용된 서버 이름이 trusted여야 한다" }
        }

        @Test
        fun `register은(는) return bad request when server is blocked by allowlist해야 한다`() = runTest {
            val secureStore = InMemoryMcpServerStore()
            val secureManager = DefaultMcpManager(
                securityConfig = McpSecurityConfig(allowedServerNames = setOf("trusted")),
                store = secureStore
            )
            val secureController = McpServerController(
                secureManager, secureStore, InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties()
            )

            val blockedReq = RegisterMcpServerRequest(
                name = "untrusted",
                transportType = "SSE",
                config = mapOf("url" to "http://example.com:8081/sse"),
                autoConnect = false
            )

            val blockedResp = secureController.registerServer(blockedReq, adminExchange())
            assertEquals(HttpStatus.BAD_REQUEST, blockedResp.statusCode) {
                "허용 목록에 없는 서버 등록은 400이어야 한다"
            }

            assertNull(secureStore.findByName("untrusted")) {
                "허용 목록에 의해 차단된 서버는 스토어에 저장되지 않아야 한다"
            }
        }

        @Test
        fun `register은(는) persist when runtime manager and store are decoupled해야 한다`() = runTest {
            val runtimeManager = DefaultMcpManager()
            val persistentStore = InMemoryMcpServerStore()
            val localController = McpServerController(
                runtimeManager, persistentStore,
                InMemoryAdminAuditStore(), com.arc.reactor.agent.config.AgentProperties()
            )

            val request = RegisterMcpServerRequest(
                name = "decoupled-register",
                transportType = "SSE",
                config = mapOf("url" to "http://example.com:8081/sse"),
                autoConnect = false
            )

            val response = localController.registerServer(request, adminExchange())
            assertEquals(HttpStatus.CREATED, response.statusCode) {
                "매니저와 스토어가 분리된 환경에서도 서버 등록은 201이어야 한다"
            }
            assertNotNull(persistentStore.findByName("decoupled-register")) {
                "등록 엔드포인트는 서버를 컨트롤러 스토어에 저장해야 한다"
            }

            val detailResponse = localController.getServer("decoupled-register", adminExchange())
            assertEquals(HttpStatus.OK, detailResponse.statusCode) {
                "분리된 환경에서 등록된 서버의 상세 조회는 200이어야 한다"
            }
        }
    }
}
