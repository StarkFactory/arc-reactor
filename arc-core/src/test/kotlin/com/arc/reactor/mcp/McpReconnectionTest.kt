package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * MCP auto-reconnection tests.
 *
 * Covers: reconnection scheduling, ensureConnected on-demand reconnection,
 * disabled reconnection, close lifecycle with reconnection scope.
 *
 * Uses STDIO servers with missing command config for instant failure
 * (avoids 10-20s STDIO process timeout).
 */
class McpReconnectionTest {

    /** STDIO server with missing command — fails instantly in connectStdio(). */
    private fun fastFailServer(name: String) = McpServer(
        name = name,
        transportType = McpTransportType.STDIO,
        config = emptyMap() // Missing 'command' → immediate failure
    )

    @Nested
    inner class ReconnectionEnabled {

        @Test
        fun `failed connect should schedule background reconnection`() = runBlocking {
            val props = McpReconnectionProperties(
                enabled = true,
                maxAttempts = 2,
                initialDelayMs = 100,
                multiplier = 1.0,
                maxDelayMs = 200
            )
            val manager = DefaultMcpManager(reconnectionProperties = props)
            manager.register(fastFailServer("recon-server"))

            // First connect fails instantly (missing command)
            val connected = manager.connect("recon-server")
            assertFalse(connected) { "Connection should fail for server with missing command" }
            assertEquals(McpServerStatus.FAILED, manager.getStatus("recon-server")) {
                "Status should be FAILED after failed connection"
            }

            // Background reconnection is scheduled but will also fail
            // Wait long enough for 2 attempts (100ms + 100ms delays + execution overhead)
            delay(3000)

            // After exhausted attempts, status should still be FAILED
            assertEquals(McpServerStatus.FAILED, manager.getStatus("recon-server")) {
                "Status should remain FAILED after exhausted reconnection attempts"
            }

            manager.close()
        }

        @Test
        fun `close should cancel background reconnection tasks`() = runBlocking {
            val props = McpReconnectionProperties(
                enabled = true,
                maxAttempts = 100,
                initialDelayMs = 5000, // Long delay so task is still waiting
                multiplier = 1.0,
                maxDelayMs = 5000
            )
            val manager = DefaultMcpManager(reconnectionProperties = props)
            manager.register(fastFailServer("long-recon"))

            // Trigger background reconnection
            manager.connect("long-recon")

            // Close should cancel the reconnection scope without blocking
            manager.close()

            // No assertion needed - test passes if close() doesn't hang
        }

        @Test
        fun `disconnect should cause background reconnection to exit`() = runBlocking {
            val props = McpReconnectionProperties(
                enabled = true,
                maxAttempts = 10,
                initialDelayMs = 200,
                multiplier = 1.0,
                maxDelayMs = 500
            )
            val manager = DefaultMcpManager(reconnectionProperties = props)
            manager.register(fastFailServer("manual-recon"))

            // Initial connect fails, schedules background reconnection
            manager.connect("manual-recon")

            // Disconnect manually — background task should detect DISCONNECTED and exit
            manager.disconnect("manual-recon")

            // Wait a bit for the reconnection loop to detect disconnected state
            delay(500)

            assertEquals(McpServerStatus.DISCONNECTED, manager.getStatus("manual-recon")) {
                "Status should be DISCONNECTED after manual disconnect"
            }

            manager.close()
        }
    }

    @Nested
    inner class ReconnectionDisabled {

        @Test
        fun `failed connect should not schedule reconnection when disabled`() = runBlocking {
            val props = McpReconnectionProperties(enabled = false)
            val manager = DefaultMcpManager(reconnectionProperties = props)
            manager.register(fastFailServer("no-recon"))

            val connected = manager.connect("no-recon")
            assertFalse(connected) { "Connection should fail" }
            assertEquals(McpServerStatus.FAILED, manager.getStatus("no-recon")) {
                "Status should be FAILED"
            }

            // No background task scheduled — state should remain stable
            delay(200)
            assertEquals(McpServerStatus.FAILED, manager.getStatus("no-recon")) {
                "Status should remain FAILED with reconnection disabled"
            }

            manager.close()
        }
    }

    @Nested
    inner class EnsureConnected {

        @Test
        fun `ensureConnected returns false for PENDING status`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(enabled = true)
            )
            manager.register(fastFailServer("ensure-test"))

            // PENDING status — not FAILED or DISCONNECTED, so ensureConnected returns false
            val result = manager.ensureConnected("ensure-test")
            assertFalse(result) {
                "ensureConnected should return false for PENDING status (not reconnectable)"
            }

            manager.close()
        }

        @Test
        fun `ensureConnected attempts reconnect for FAILED status`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(
                    enabled = true,
                    maxAttempts = 1,
                    initialDelayMs = 100
                )
            )
            manager.register(fastFailServer("ensure-failed"))

            // Force FAILED status
            manager.connect("ensure-failed")
            assertEquals(McpServerStatus.FAILED, manager.getStatus("ensure-failed")) {
                "Status should be FAILED after connection failure"
            }

            // ensureConnected should attempt reconnect (will fail again)
            val result = manager.ensureConnected("ensure-failed")
            assertFalse(result) {
                "ensureConnected should return false when reconnect also fails"
            }

            manager.close()
        }

        @Test
        fun `ensureConnected attempts reconnect for DISCONNECTED status`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(
                    enabled = true,
                    maxAttempts = 1,
                    initialDelayMs = 100
                )
            )
            manager.register(fastFailServer("ensure-disconnected"))

            // Force DISCONNECTED status
            manager.disconnect("ensure-disconnected")
            assertEquals(McpServerStatus.DISCONNECTED, manager.getStatus("ensure-disconnected")) {
                "Status should be DISCONNECTED"
            }

            // ensureConnected should attempt reconnect
            val result = manager.ensureConnected("ensure-disconnected")
            assertFalse(result) {
                "ensureConnected should return false when reconnect fails"
            }

            manager.close()
        }

        @Test
        fun `ensureConnected returns false when reconnection is disabled`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(enabled = false)
            )
            manager.register(fastFailServer("no-recon-ensure"))

            // Force FAILED status
            manager.connect("no-recon-ensure")

            val result = manager.ensureConnected("no-recon-ensure")
            assertFalse(result) {
                "ensureConnected should return false when reconnection is disabled"
            }

            manager.close()
        }
    }

    @Nested
    inner class ReconnectionProperties {

        @Test
        fun `default properties should have sensible values`() {
            val props = McpReconnectionProperties()

            assertTrue(props.enabled) { "Reconnection should be enabled by default" }
            assertEquals(5, props.maxAttempts) { "Default maxAttempts should be 5" }
            assertEquals(5000, props.initialDelayMs) { "Default initialDelayMs should be 5000" }
            assertEquals(2.0, props.multiplier) { "Default multiplier should be 2.0" }
            assertEquals(60_000, props.maxDelayMs) { "Default maxDelayMs should be 60000" }
        }

        @Test
        fun `custom properties should be applied`() {
            val props = McpReconnectionProperties(
                enabled = false,
                maxAttempts = 3,
                initialDelayMs = 1000,
                multiplier = 1.5,
                maxDelayMs = 30_000
            )

            assertFalse(props.enabled) { "Custom enabled should be false" }
            assertEquals(3, props.maxAttempts) { "Custom maxAttempts should be 3" }
            assertEquals(1000, props.initialDelayMs) { "Custom initialDelayMs should be 1000" }
            assertEquals(1.5, props.multiplier) { "Custom multiplier should be 1.5" }
            assertEquals(30_000, props.maxDelayMs) { "Custom maxDelayMs should be 30000" }
        }

        @Test
        fun `DefaultMcpManager should accept reconnection properties`() {
            val props = McpReconnectionProperties(enabled = false, maxAttempts = 10)
            val manager = DefaultMcpManager(reconnectionProperties = props)

            assertNotNull(manager) { "Manager should be created with custom reconnection properties" }
            manager.close()
        }
    }

    @Nested
    inner class UnregisterDuringReconnection {

        @Test
        fun `unregister should stop reconnection for that server`() = runBlocking {
            val props = McpReconnectionProperties(
                enabled = true,
                maxAttempts = 10,
                initialDelayMs = 500,
                multiplier = 1.0,
                maxDelayMs = 1000
            )
            val manager = DefaultMcpManager(reconnectionProperties = props)
            manager.register(fastFailServer("unregister-recon"))

            // Trigger background reconnection
            manager.connect("unregister-recon")

            // Unregister the server — reconnection task should detect and exit
            manager.unregister("unregister-recon")

            // Wait for reconnection task to notice
            delay(200)

            assertNull(manager.getStatus("unregister-recon")) {
                "Status should be null after unregister"
            }

            manager.close()
        }
    }
}
