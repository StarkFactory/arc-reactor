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

class McpServerHealthIndicatorTest {

    private val mcpManager = mockk<McpManager>()
    private val indicator = McpServerHealthIndicator(mcpManager)

    @Test
    fun `health is UP when no servers registered`() {
        every { mcpManager.listServers() } returns emptyList()

        val health = indicator.health()

        health.status shouldBe Status.UP
        health.details["totalServers"] shouldBe 0
    }

    @Test
    fun `health is UP when all servers connected`() {
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
    fun `health is DEGRADED when some servers failed`() {
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
    fun `health is DOWN when all servers failed`() {
        val servers = listOf(testServer("server-a"))
        every { mcpManager.listServers() } returns servers
        every { mcpManager.getStatus("server-a") } returns McpServerStatus.FAILED

        val health = indicator.health()

        health.status shouldBe Status.DOWN
    }

    @Test
    fun `health includes per-server status map`() {
        val servers = listOf(testServer("server-a"))
        every { mcpManager.listServers() } returns servers
        every { mcpManager.getStatus("server-a") } returns McpServerStatus.PENDING

        val health = indicator.health()

        @Suppress("UNCHECKED_CAST")
        val statusMap = health.details["servers"] as Map<String, McpServerStatus>
        statusMap["server-a"] shouldBe McpServerStatus.PENDING
    }

    @Test
    fun `null status defaults to PENDING`() {
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
