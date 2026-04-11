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
        fun `ensureConnected은(는) PENDING + autoConnect=false 서버를 건너뛴다`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(enabled = true)
            )
            // fastFailServer는 autoConnect=false (default) — 수동 관리 서버
            manager.register(fastFailServer("ensure-test"))

            // R332: PENDING + autoConnect=false는 사용자의 명시적 connect()를 기다린다
            val result = manager.ensureConnected("ensure-test")
            assertFalse(result) {
                "autoConnect=false 서버는 PENDING 상태에서 ensureConnected가 false를 반환해야 한다"
            }

            manager.close()
        }

        /**
         * R332 regression: `McpHealthPinger`가 PENDING 서버에 `ensureConnected`를 호출하면
         * `autoConnect=true` 서버는 첫 연결을 시도해야 한다. 이전에는 `ensureConnected`가
         * PENDING 상태를 거부해 HealthPinger의 R173 의도가 무효화되어 있었다.
         *
         * `fastFailServer`를 autoConnect=true로 만들어 첫 연결이 즉시 실패하도록 유도한 뒤,
         * 상태가 PENDING → FAILED로 실제 전이되었는지 확인한다. 이는 ensureConnected가
         * connect()까지 실제로 도달했다는 증거다.
         */
        @Test
        fun `R332 ensureConnected은(는) PENDING + autoConnect=true 서버의 첫 연결을 시도한다`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(enabled = true)
            )
            // autoConnect=true로 명시한 서버 — HealthPinger가 방치해서는 안 되는 케이스
            val server = McpServer(
                name = "ensure-pending-auto",
                transportType = McpTransportType.STDIO,
                config = emptyMap(), // 'command' 누락 → 연결 시도는 실패
                autoConnect = true
            )
            manager.register(server)
            assertEquals(McpServerStatus.PENDING, manager.getStatus("ensure-pending-auto")) {
                "register 직후 상태는 PENDING이어야 한다"
            }

            // R332: ensureConnected는 connect()까지 도달하고, connect가 즉시 실패하면 FAILED로 전이
            val result = manager.ensureConnected("ensure-pending-auto")
            assertFalse(result) {
                "연결 자체는 command 누락으로 실패해야 하나, ensureConnected는 connect()를 시도해야 한다"
            }
            assertEquals(McpServerStatus.FAILED, manager.getStatus("ensure-pending-auto")) {
                "ensureConnected가 connect()에 도달했음을 입증 — PENDING에서 FAILED로 전이되어야 한다"
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
