package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.tool.ToolCallback
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpToolAvailabilityCheckerTest {

    private val mcpManager = mockk<McpManager>(relaxed = true)
    private val checker = McpToolAvailabilityChecker(mcpManager)

    private fun server(name: String): McpServer = McpServer(
        name = name,
        transportType = McpTransportType.SSE,
        config = mapOf("url" to "http://example.com/sse")
    )

    private fun toolCallback(name: String): ToolCallback {
        val cb = mockk<ToolCallback>()
        every { cb.name } returns name
        return cb
    }

    @Test
    fun `빈 도구 목록을 요청하면 빈 결과를 반환한다`() {
        val result = checker.check(emptyList())
        assertTrue(result.allAvailable, "빈 요청은 allAvailable이어야 한다")
        assertTrue(result.available.isEmpty(), "available이 비어있어야 한다")
        assertTrue(result.unavailable.isEmpty(), "unavailable이 비어있어야 한다")
        assertTrue(result.degraded.isEmpty(), "degraded가 비어있어야 한다")
    }

    @Test
    fun `CONNECTED 서버의 도구는 available로 분류한다`() {
        val serverA = server("serverA")
        val tool1 = toolCallback("search")
        val tool2 = toolCallback("read")

        every { mcpManager.getAllToolCallbacks() } returns listOf(tool1, tool2)
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
        every { mcpManager.getToolCallbacks("serverA") } returns listOf(tool1, tool2)

        val result = checker.check(listOf("search", "read"))

        assertTrue(result.allAvailable, "모든 도구가 available이어야 한다")
        assertEquals(listOf("search", "read"), result.available, "available 목록이 일치해야 한다")
        assertTrue(result.unavailable.isEmpty(), "unavailable이 비어있어야 한다")
        assertTrue(result.degraded.isEmpty(), "degraded가 비어있어야 한다")
    }

    @Test
    fun `등록되지 않은 도구는 unavailable로 분류한다`() {
        every { mcpManager.getAllToolCallbacks() } returns emptyList()
        every { mcpManager.listServers() } returns emptyList()

        val result = checker.check(listOf("nonexistent_tool"))

        assertFalse(result.allAvailable, "allAvailable이 false여야 한다")
        assertTrue(result.available.isEmpty(), "available이 비어있어야 한다")
        assertEquals(
            listOf("nonexistent_tool"), result.unavailable,
            "unavailable에 미등록 도구가 포함되어야 한다"
        )
    }

    @Test
    fun `FAILED 서버의 도구는 degraded로 분류한다`() {
        val serverA = server("serverA")
        val tool1 = toolCallback("search")

        every { mcpManager.getAllToolCallbacks() } returns listOf(tool1)
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.FAILED
        every { mcpManager.getToolCallbacks("serverA") } returns listOf(tool1)

        val result = checker.check(listOf("search"))

        assertFalse(result.allAvailable, "allAvailable이 false여야 한다")
        assertTrue(result.available.isEmpty(), "available이 비어있어야 한다")
        assertEquals(listOf("search"), result.degraded, "degraded에 도구가 포함되어야 한다")
    }

    @Test
    fun `혼합 상태의 도구를 올바르게 분류한다`() {
        val serverA = server("serverA")
        val serverB = server("serverB")
        val tool1 = toolCallback("search")
        val tool2 = toolCallback("write")

        every { mcpManager.getAllToolCallbacks() } returns listOf(tool1, tool2)
        every { mcpManager.listServers() } returns listOf(serverA, serverB)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
        every { mcpManager.getStatus("serverB") } returns McpServerStatus.FAILED
        every { mcpManager.getToolCallbacks("serverA") } returns listOf(tool1)
        every { mcpManager.getToolCallbacks("serverB") } returns listOf(tool2)

        val result = checker.check(listOf("search", "write", "nonexistent"))

        assertFalse(result.allAvailable, "allAvailable이 false여야 한다")
        assertEquals(listOf("search"), result.available, "CONNECTED 서버 도구는 available")
        assertEquals(listOf("write"), result.degraded, "FAILED 서버 도구는 degraded")
        assertEquals(listOf("nonexistent"), result.unavailable, "미등록 도구는 unavailable")
    }

    @Test
    fun `DISCONNECTED 서버의 도구는 unavailable로 분류한다`() {
        val serverA = server("serverA")
        val tool1 = toolCallback("search")

        every { mcpManager.getAllToolCallbacks() } returns listOf(tool1)
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.DISCONNECTED
        every { mcpManager.getToolCallbacks("serverA") } returns listOf(tool1)

        val result = checker.check(listOf("search"))

        assertFalse(result.allAvailable, "allAvailable이 false여야 한다")
        assertEquals(listOf("search"), result.unavailable, "DISCONNECTED 도구는 unavailable")
    }

    @Test
    fun `PENDING 서버의 도구는 unavailable로 분류한다`() {
        val serverA = server("serverA")
        val tool1 = toolCallback("search")

        every { mcpManager.getAllToolCallbacks() } returns listOf(tool1)
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.PENDING
        every { mcpManager.getToolCallbacks("serverA") } returns listOf(tool1)

        val result = checker.check(listOf("search"))

        assertFalse(result.allAvailable, "allAvailable이 false여야 한다")
        assertEquals(listOf("search"), result.unavailable, "PENDING 도구는 unavailable")
    }

    @Test
    fun `여러 서버에 같은 이름의 도구가 있으면 첫 번째 서버 기준으로 판단한다`() {
        val serverA = server("serverA")
        val serverB = server("serverB")
        val tool1 = toolCallback("search")
        val tool1b = toolCallback("search")

        every { mcpManager.getAllToolCallbacks() } returns listOf(tool1)
        every { mcpManager.listServers() } returns listOf(serverA, serverB)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
        every { mcpManager.getStatus("serverB") } returns McpServerStatus.FAILED
        every { mcpManager.getToolCallbacks("serverA") } returns listOf(tool1)
        every { mcpManager.getToolCallbacks("serverB") } returns listOf(tool1b)

        val result = checker.check(listOf("search"))

        // serverA가 먼저이므로 CONNECTED — available
        assertEquals(listOf("search"), result.available, "첫 번째 서버 기준으로 available")
    }

    @Test
    fun `allAvailable 속성이 정확하게 동작한다`() {
        val result1 = ToolAvailabilityResult(
            available = listOf("a"), unavailable = emptyList(), degraded = emptyList()
        )
        assertTrue(result1.allAvailable, "unavailable+degraded가 비어있으면 allAvailable")

        val result2 = ToolAvailabilityResult(
            available = listOf("a"), unavailable = listOf("b"), degraded = emptyList()
        )
        assertFalse(result2.allAvailable, "unavailable이 있으면 allAvailable=false")

        val result3 = ToolAvailabilityResult(
            available = listOf("a"), unavailable = emptyList(), degraded = listOf("c")
        )
        assertFalse(result3.allAvailable, "degraded가 있으면 allAvailable=false")
    }
}
