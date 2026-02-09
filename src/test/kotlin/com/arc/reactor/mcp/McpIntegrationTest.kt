package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * MCP Integration Test
 *
 * Validates the full MCP lifecycle against the official
 * `@modelcontextprotocol/server-everything` test server via STDIO transport.
 *
 * ## Prerequisites
 * - Node.js 18+ and npx on PATH
 *
 * ## Running
 * ```bash
 * ./gradlew test -PincludeIntegration
 * ```
 *
 * Excluded from default `./gradlew test` via @Tag("integration").
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class McpIntegrationTest {

    private lateinit var manager: DefaultMcpManager

    companion object {
        private const val SERVER_NAME = "everything"
        private const val NPX_PACKAGE = "@modelcontextprotocol/server-everything"
    }

    @BeforeAll
    fun setup() {
        // Verify npx is available
        val npxCheck = ProcessBuilder("npx", "--version")
            .redirectErrorStream(true)
            .start()
        val exitCode = npxCheck.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("npx not found on PATH. Install Node.js to run MCP integration tests.")
        }

        manager = DefaultMcpManager(connectionTimeoutMs = 30_000)

        manager.register(
            McpServer(
                name = SERVER_NAME,
                description = "Official MCP test server",
                transportType = McpTransportType.STDIO,
                config = mapOf(
                    "command" to "npx",
                    "args" to listOf("-y", NPX_PACKAGE, "stdio")
                )
            )
        )
    }

    @AfterAll
    fun teardown() {
        manager.close()
    }

    @Test
    @Order(1)
    fun `should connect to server-everything via STDIO`() = runBlocking {
        val connected = manager.connect(SERVER_NAME)

        assertTrue(connected) { "Should successfully connect to $NPX_PACKAGE" }
        assertEquals(McpServerStatus.CONNECTED, manager.getStatus(SERVER_NAME)) {
            "Status should be CONNECTED after successful connection"
        }
    }

    @Test
    @Order(2)
    fun `should load tools from server-everything`() {
        val tools = manager.getToolCallbacks(SERVER_NAME)

        assertTrue(tools.isNotEmpty()) {
            "Should load at least one tool from $NPX_PACKAGE"
        }

        // server-everything provides 'echo' tool
        val toolNames = tools.map { it.name }
        assertTrue(toolNames.contains("echo")) {
            "Should have 'echo' tool. Available tools: $toolNames"
        }
    }

    @Test
    @Order(3)
    fun `should list tools via getAllToolCallbacks`() {
        val allTools = manager.getAllToolCallbacks()

        assertTrue(allTools.isNotEmpty()) {
            "getAllToolCallbacks should return tools from connected servers"
        }
    }

    @Test
    @Order(4)
    fun `should call echo tool and receive response`() = runBlocking {
        val tools = manager.getToolCallbacks(SERVER_NAME)
        val echoTool = tools.find { it.name == "echo" }

        assertNotNull(echoTool) { "echo tool should exist" }

        val result = echoTool!!.call(mapOf("message" to "Hello from Arc Reactor"))

        assertNotNull(result) { "Tool call result should not be null" }
        val resultStr = result.toString()
        assertTrue(resultStr.contains("Hello from Arc Reactor")) {
            "Echo tool should return the input message. Got: $resultStr"
        }
    }

    @Test
    @Order(5)
    fun `should have valid inputSchema for echo tool`() {
        val tools = manager.getToolCallbacks(SERVER_NAME)
        val echoTool = tools.find { it.name == "echo" }

        assertNotNull(echoTool) { "echo tool should exist" }

        val schema = echoTool!!.inputSchema
        assertTrue(schema.contains("message")) {
            "Echo tool inputSchema should contain 'message' property. Got: $schema"
        }
    }

    @Test
    @Order(6)
    fun `should disconnect gracefully`() = runBlocking {
        manager.disconnect(SERVER_NAME)

        assertEquals(McpServerStatus.DISCONNECTED, manager.getStatus(SERVER_NAME)) {
            "Status should be DISCONNECTED after disconnect"
        }

        val tools = manager.getToolCallbacks(SERVER_NAME)
        assertTrue(tools.isEmpty()) {
            "Tool callbacks should be cleared after disconnect"
        }
    }
}
