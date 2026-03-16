package com.arc.reactor.health

import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status

/**
 * McpServerHealthIndicator에 대한 테스트.
 *
 * MCP 서버 헬스 체크 로직을 검증합니다.
 */
class McpServerHealthIndicatorTest {

    private val mcpManager = mockk<McpManager>()
    private val indicator = McpServerHealthIndicator(mcpManager)

    @Test
    fun `no servers registered일 때 health은(는) UP이다`() {
        every { mcpManager.listServers() } returns emptyList()

        val health = indicator.health()

        health.status shouldBe Status.UP
        health.details["totalServers"] shouldBe 0
    }

    @Test
    fun `all servers connected일 때 health은(는) UP이다`() {
        val servers = listOf(testServer("server-a"), testServer("server-b"))
        every { mcpManager.listServers() } returns servers
        every { mcpManager.getStatus("server-a") } returns McpServerStatus.CONNECTED
        every { mcpManager.getStatus("server-b") } returns McpServerStatus.CONNECTED

        val health = indicator.health()

        health.status shouldBe Status.UP
        health.details["connected"] shouldBe 2
        health.details["failed"] shouldBe 0
    }

    @Test
    fun `some servers failed일 때 health은(는) DEGRADED이다`() {
        val servers = listOf(testServer("server-a"), testServer("server-b"))
        every { mcpManager.listServers() } returns servers
        every { mcpManager.getStatus("server-a") } returns McpServerStatus.CONNECTED
        every { mcpManager.getStatus("server-b") } returns McpServerStatus.FAILED

        val health = indicator.health()

        health.status.code shouldBe "DEGRADED"
        health.details["connected"] shouldBe 1
        health.details["failed"] shouldBe 1
    }

    @Test
    fun `all servers failed일 때 health은(는) DOWN이다`() {
        val servers = listOf(testServer("server-a"))
        every { mcpManager.listServers() } returns servers
        every { mcpManager.getStatus("server-a") } returns McpServerStatus.FAILED

        val health = indicator.health()

        health.status shouldBe Status.DOWN
    }

    @Test
    fun `헬스 includes per-server status map`() {
        val servers = listOf(testServer("server-a"))
        every { mcpManager.listServers() } returns servers
        every { mcpManager.getStatus("server-a") } returns McpServerStatus.PENDING

        val health = indicator.health()

        @Suppress("UNCHECKED_CAST")
        val statusMap = health.details["servers"] as Map<String, McpServerStatus>
        statusMap["server-a"] shouldBe McpServerStatus.PENDING
    }

    @Test
    fun `null인 status defaults to PENDING`() {
        val servers = listOf(testServer("server-a"))
        every { mcpManager.listServers() } returns servers
        every { mcpManager.getStatus("server-a") } returns null

        val health = indicator.health()

        @Suppress("UNCHECKED_CAST")
        val statusMap = health.details["servers"] as Map<String, McpServerStatus>
        statusMap["server-a"] shouldBe McpServerStatus.PENDING
    }

    private fun testServer(name: String): McpServer = McpServer(
        name = name,
        transportType = McpTransportType.SSE,
        config = mapOf("url" to "http://localhost:8081/sse")
    )
}
