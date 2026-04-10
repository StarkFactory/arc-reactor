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
 * MCP 자동 재연결에 대한 테스트.
 *
 * 재연결 스케줄링, ensureConnected 온디맨드 재연결,
 * 비활성화된 재연결, 재연결 스코프가 포함된 종료 라이프사이클을 검증합니다.
 *
 * 즉시 실패를 위해 커맨드 설정이 누락된 STDIO 서버를 사용합니다
 * (10-20초 STDIO 프로세스 타임아웃을 회피).
 */
class McpReconnectionTest {

    /** 커맨드가 누락된 STDIO 서버 — connectStdio()에서 즉시 실패합니다. */
    private fun fastFailServer(name: String) = McpServer(
        name = name,
        transportType = McpTransportType.STDIO,
        config = emptyMap()  // Missing 'command' → immediate failure
    )

    @Nested
    inner class ReconnectionEnabled {

        @Test
        fun `failed connect은(는) schedule background reconnection해야 한다`() = runBlocking {
            val props = McpReconnectionProperties(
                enabled = true,
                maxAttempts = 2,
                initialDelayMs = 20,
                multiplier = 1.0,
                maxDelayMs = 20
            )
            val manager = DefaultMcpManager(reconnectionProperties = props)
            manager.register(fastFailServer("recon-server"))

            // 첫 번째 연결이 즉시 실패합니다 (커맨드 누락)
            val connected = manager.connect("recon-server")
            assertFalse(connected) { "Connection should fail for server with missing command" }
            assertEquals(McpServerStatus.FAILED, manager.getStatus("recon-server")) {
                "Status should be FAILED after failed connection"
            }

            // 백그라운드 재연결이 스케줄되었지만 역시 실패할 것입니다.
            // 짧은 백오프로 2회 시도하기에 충분한 시간을 기다립니다
            delay(500)

            // 재시도가 소진된 후에도 상태는 여전히 FAILED여야 합니다
            assertEquals(McpServerStatus.FAILED, manager.getStatus("recon-server")) {
                "Status should remain FAILED after exhausted reconnection attempts"
            }

            manager.close()
        }

        @Test
        fun `close은(는) cancel background reconnection tasks해야 한다`() = runBlocking {
            val props = McpReconnectionProperties(
                enabled = true,
                maxAttempts = 100,
                initialDelayMs = 5000, // Long delay so task is still waiting
                multiplier = 1.0,
                maxDelayMs = 5000
            )
            val manager = DefaultMcpManager(reconnectionProperties = props)
            manager.register(fastFailServer("long-recon"))

            // background reconnection를 트리거합니다
            manager.connect("long-recon")

            // Close은(는) cancel the reconnection scope without blocking해야 합니다
            manager.close()

            // assertion needed - test passes if close() doesn't hang 없음
        }

        @Test
        fun `disconnect은(는) cause background reconnection to exit해야 한다`() = runBlocking {
            val props = McpReconnectionProperties(
                enabled = true,
                maxAttempts = 10,
                initialDelayMs = 50,
                multiplier = 1.0,
                maxDelayMs = 50
            )
            val manager = DefaultMcpManager(reconnectionProperties = props)
            manager.register(fastFailServer("manual-recon"))

            // Initial connect fails, schedules background reconnection
            manager.connect("manual-recon")

            // Disconnect manually — background task은(는) detect DISCONNECTED and exit해야 합니다
            manager.disconnect("manual-recon")

            // a bit for the reconnection loop to detect disconnected state를 기다립니다
            delay(120)

            assertEquals(McpServerStatus.DISCONNECTED, manager.getStatus("manual-recon")) {
                "Status should be DISCONNECTED after manual disconnect"
            }

            manager.close()
        }
    }

    @Nested
    inner class ReconnectionDisabled {

        @Test
        fun `failed connect은(는) not schedule reconnection when disabled해야 한다`() = runBlocking {
            val props = McpReconnectionProperties(enabled = false)
            val manager = DefaultMcpManager(reconnectionProperties = props)
            manager.register(fastFailServer("no-recon"))

            val connected = manager.connect("no-recon")
            assertFalse(connected) { "Connection should fail" }
            assertEquals(McpServerStatus.FAILED, manager.getStatus("no-recon")) {
                "Status should be FAILED"
            }

            // 백그라운드 작업이 스케줄되지 않음 — 상태가 안정적으로 유지되어야 합니다
            delay(50)
            assertEquals(McpServerStatus.FAILED, manager.getStatus("no-recon")) {
                "Status should remain FAILED with reconnection disabled"
            }

            manager.close()
        }
    }

    @Nested
    inner class EnsureConnected {

        @Test
        fun `ensureConnected은(는) returns false for PENDING status`() = runBlocking {
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
        fun `ensureConnected은(는) attempts reconnect for FAILED status`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(
                    enabled = true,
                    maxAttempts = 1,
                    initialDelayMs = 100
                )
            )
            manager.register(fastFailServer("ensure-failed"))

            // FAILED status를 강제합니다
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
        fun `ensureConnected은(는) attempts reconnect for DISCONNECTED status`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(
                    enabled = true,
                    maxAttempts = 1,
                    initialDelayMs = 100
                )
            )
            manager.register(fastFailServer("ensure-disconnected"))

            // DISCONNECTED status를 강제합니다
            manager.disconnect("ensure-disconnected")
            assertEquals(McpServerStatus.DISCONNECTED, manager.getStatus("ensure-disconnected")) {
                "Status should be DISCONNECTED"
            }

            // ensureConnected은(는) attempt reconnect해야 합니다
            val result = manager.ensureConnected("ensure-disconnected")
            assertFalse(result) {
                "ensureConnected should return false when reconnect fails"
            }

            manager.close()
        }

        @Test
        fun `ensureConnected returns false when reconnection은(는) disabled이다`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(enabled = false)
            )
            manager.register(fastFailServer("no-recon-ensure"))

            // FAILED status를 강제합니다
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
        fun `default properties은(는) have sensible values해야 한다`() {
            val props = McpReconnectionProperties()

            assertTrue(props.enabled) { "Reconnection should be enabled by default" }
            // R173: MCP 시작 시 늦게 올라오는 환경 대응 — maxAttempts 5→10, initialDelay 5s→2s
            assertEquals(10, props.maxAttempts) { "Default maxAttempts should be 10 (R173)" }
            assertEquals(2000, props.initialDelayMs) { "Default initialDelayMs should be 2000 (R173)" }
            assertEquals(2.0, props.multiplier) { "Default multiplier should be 2.0" }
            assertEquals(60_000, props.maxDelayMs) { "Default maxDelayMs should be 60000" }
        }

        @Test
        fun `custom properties은(는) be applied해야 한다`() {
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
        fun `DefaultMcpManager은(는) accept reconnection properties해야 한다`() {
            val props = McpReconnectionProperties(enabled = false, maxAttempts = 10)
            val manager = DefaultMcpManager(reconnectionProperties = props)

            assertNotNull(manager) { "Manager should be created with custom reconnection properties" }
            manager.close()
        }
    }

    @Nested
    inner class UnregisterDuringReconnection {

        @Test
        fun `unregister은(는) stop reconnection for that server해야 한다`() = runBlocking {
            val props = McpReconnectionProperties(
                enabled = true,
                maxAttempts = 10,
                initialDelayMs = 500,
                multiplier = 1.0,
                maxDelayMs = 1000
            )
            val manager = DefaultMcpManager(reconnectionProperties = props)
            manager.register(fastFailServer("unregister-recon"))

            // background reconnection를 트리거합니다
            manager.connect("unregister-recon")

            // Unregister the server — reconnection task은(는) detect and exit해야 합니다
            manager.unregister("unregister-recon")

            // for reconnection task to notice를 기다립니다
            delay(200)

            assertNull(manager.getStatus("unregister-recon")) {
                "Status should be null after unregister"
            }

            manager.close()
        }
    }
}
