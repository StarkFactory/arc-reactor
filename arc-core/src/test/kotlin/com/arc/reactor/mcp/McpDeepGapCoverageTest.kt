package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpHealthProperties
import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 컴포넌트 심층 갭 커버리지 테스트.
 *
 * 기존 테스트에서 다루지 않은 엣지 케이스를 보강한다:
 * - McpReconnectionCoordinator: clearAll, CONNECTED/DISCONNECTED 조기 종료, 서버 미존재 종료
 * - McpToolAvailabilityChecker: getToolCallbacks 예외, getStatus null 반환
 * - McpHealthPinger: 중복 start, close 위임, DISCONNECTED 서버 처리
 * - deduplicateCallbacksByName: 알파벳 순 우선순위, onDuplicate 콜백, 단일 서버
 * - McpManager.ensureConnected: CONNECTING 상태, PENDING 상태, 미등록 서버
 * - McpSecurityConfig: DEFAULT_ALLOWED_STDIO_COMMANDS 불변성
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpDeepGapCoverageTest {

    // ── McpReconnectionCoordinator ────────────────────────────────────────────

    @Nested
    inner class ReconnectionCoordinatorDeep {

        /**
         * clearAll이 모든 서버의 재연결 잡을 취소해야 한다.
         * 기존 테스트는 단일 서버 clear만 검증한다.
         */
        @Test
        fun `clearAll은 스케줄된 모든 서버의 재연결 잡을 취소해야 한다`() = runTest {
            val reconnectCount = AtomicInteger(0)
            val coordinator = McpReconnectionCoordinator(
                scope = backgroundScope,
                properties = McpReconnectionProperties(
                    enabled = true,
                    maxAttempts = 5,
                    initialDelayMs = 5_000,
                    multiplier = 1.0,
                    maxDelayMs = 5_000
                ),
                statusProvider = { McpServerStatus.FAILED },
                serverExists = { true },
                reconnectAction = {
                    reconnectCount.incrementAndGet()
                    false
                }
            )

            coordinator.schedule("server-a")
            coordinator.schedule("server-b")
            coordinator.schedule("server-c")

            coordinator.clearAll()

            advanceTimeBy(15_000)

            assertEquals(0, reconnectCount.get()) {
                "clearAll 후에는 재연결 시도가 없어야 한다"
            }
        }

        /**
         * serverExists가 false를 반환하면 재연결 루프를 즉시 중단해야 한다.
         * 서버가 unregister된 경우 불필요한 재연결 시도를 방지한다.
         */
        @Test
        fun `serverExists가 false이면 재연결 루프를 즉시 중단해야 한다`() = runTest {
            val reconnectCount = AtomicInteger(0)
            var serverExists = true

            val coordinator = McpReconnectionCoordinator(
                scope = backgroundScope,
                properties = McpReconnectionProperties(
                    enabled = true,
                    maxAttempts = 5,
                    initialDelayMs = 200,
                    multiplier = 1.0,
                    maxDelayMs = 200
                ),
                statusProvider = { McpServerStatus.FAILED },
                serverExists = { serverExists },
                reconnectAction = {
                    reconnectCount.incrementAndGet()
                    false
                }
            )

            coordinator.schedule("removed-server")
            serverExists = false

            advanceTimeBy(2_000)

            assertTrue(reconnectCount.get() <= 1) {
                "서버 미존재 감지 후에는 추가 재연결 시도가 없어야 한다 (실제: ${reconnectCount.get()})"
            }
        }

        /**
         * 서버가 이미 CONNECTED 상태가 되면 재연결 루프를 중단해야 한다.
         * 외부에서 연결이 복구된 경우 불필요한 재시도를 방지한다.
         */
        @Test
        fun `서버가 CONNECTED 상태이면 재연결 루프를 조기 종료해야 한다`() = runTest {
            val reconnectCount = AtomicInteger(0)
            var status = McpServerStatus.FAILED

            val coordinator = McpReconnectionCoordinator(
                scope = backgroundScope,
                properties = McpReconnectionProperties(
                    enabled = true,
                    maxAttempts = 5,
                    initialDelayMs = 100,
                    multiplier = 1.0,
                    maxDelayMs = 100
                ),
                statusProvider = { status },
                serverExists = { true },
                reconnectAction = {
                    reconnectCount.incrementAndGet()
                    false
                }
            )

            coordinator.schedule("server-x")
            // 첫 번째 지연 후 CONNECTED로 변경
            advanceTimeBy(150)
            status = McpServerStatus.CONNECTED
            advanceTimeBy(1_000)

            assertTrue(reconnectCount.get() <= 1) {
                "CONNECTED 감지 후 추가 재연결 시도가 없어야 한다 (실제: ${reconnectCount.get()})"
            }
        }

        /**
         * 서버가 DISCONNECTED 상태가 되면 재연결 루프를 중단해야 한다.
         * 수동 disconnect 후 자동 재연결을 중단해야 한다.
         */
        @Test
        fun `서버가 DISCONNECTED 상태이면 재연결 루프를 조기 종료해야 한다`() = runTest {
            val reconnectCount = AtomicInteger(0)
            var status = McpServerStatus.FAILED

            val coordinator = McpReconnectionCoordinator(
                scope = backgroundScope,
                properties = McpReconnectionProperties(
                    enabled = true,
                    maxAttempts = 5,
                    initialDelayMs = 100,
                    multiplier = 1.0,
                    maxDelayMs = 100
                ),
                statusProvider = { status },
                serverExists = { true },
                reconnectAction = {
                    reconnectCount.incrementAndGet()
                    false
                }
            )

            coordinator.schedule("server-y")
            advanceTimeBy(150)
            status = McpServerStatus.DISCONNECTED
            advanceTimeBy(1_000)

            assertTrue(reconnectCount.get() <= 1) {
                "DISCONNECTED 감지 후 추가 재연결 시도가 없어야 한다 (실제: ${reconnectCount.get()})"
            }
        }

        /**
         * 이미 활성 잡이 있으면 중복 스케줄링하지 않아야 한다.
         */
        @Test
        fun `이미 활성 잡이 있으면 중복 스케줄링을 무시해야 한다`() = runTest {
            val reconnectCount = AtomicInteger(0)

            val coordinator = McpReconnectionCoordinator(
                scope = backgroundScope,
                properties = McpReconnectionProperties(
                    enabled = true,
                    maxAttempts = 5,
                    initialDelayMs = 5_000,
                    multiplier = 1.0,
                    maxDelayMs = 5_000
                ),
                statusProvider = { McpServerStatus.FAILED },
                serverExists = { true },
                reconnectAction = {
                    reconnectCount.incrementAndGet()
                    false
                }
            )

            // 같은 서버에 여러 번 schedule 호출
            coordinator.schedule("dup-server")
            coordinator.schedule("dup-server")
            coordinator.schedule("dup-server")

            advanceTimeBy(6_000)

            // 최대 1회 시도만 수행되어야 한다
            assertTrue(reconnectCount.get() <= 1) {
                "중복 스케줄링은 무시되어야 한다 (실제: ${reconnectCount.get()})"
            }
        }

        /**
         * 재연결이 비활성화된 경우 schedule이 즉시 반환해야 한다.
         */
        @Test
        fun `재연결이 비활성화된 경우 schedule이 아무것도 하지 않아야 한다`() = runTest {
            val reconnectCount = AtomicInteger(0)

            val coordinator = McpReconnectionCoordinator(
                scope = backgroundScope,
                properties = McpReconnectionProperties(
                    enabled = false,
                    maxAttempts = 5,
                    initialDelayMs = 10,
                    multiplier = 1.0,
                    maxDelayMs = 10
                ),
                statusProvider = { McpServerStatus.FAILED },
                serverExists = { true },
                reconnectAction = {
                    reconnectCount.incrementAndGet()
                    false
                }
            )

            coordinator.schedule("disabled-server")
            advanceTimeBy(5_000)

            assertEquals(0, reconnectCount.get()) {
                "재연결 비활성화 시 재연결 시도가 없어야 한다"
            }
        }

        /**
         * 성공한 재연결 후 루프가 즉시 종료되어야 한다.
         */
        @Test
        fun `재연결 성공 시 루프를 즉시 종료해야 한다`() = runTest {
            val reconnectCount = AtomicInteger(0)

            val coordinator = McpReconnectionCoordinator(
                scope = backgroundScope,
                properties = McpReconnectionProperties(
                    enabled = true,
                    maxAttempts = 5,
                    initialDelayMs = 100,
                    multiplier = 1.0,
                    maxDelayMs = 100
                ),
                statusProvider = { McpServerStatus.FAILED },
                serverExists = { true },
                reconnectAction = {
                    reconnectCount.incrementAndGet()
                    true // 첫 번째 시도에서 성공
                }
            )

            coordinator.schedule("success-server")
            advanceTimeBy(1_000)

            assertEquals(1, reconnectCount.get()) {
                "재연결 성공 후 추가 시도가 없어야 한다"
            }
        }
    }

    // ── McpToolAvailabilityChecker 갭 ─────────────────────────────────────────

    @Nested
    inner class ToolAvailabilityCheckerDeep {

        private fun toolCallback(name: String): ToolCallback {
            val cb = mockk<ToolCallback>()
            every { cb.name } returns name
            return cb
        }

        private fun server(name: String) = McpServer(
            name = name,
            transportType = McpTransportType.SSE,
            config = mapOf("url" to "http://example.com/sse")
        )

        /**
         * 특정 서버의 getToolCallbacks 호출이 예외를 던지면
         * 해당 서버를 건너뛰고 나머지 서버를 정상 처리해야 한다.
         * 기존 테스트에서 미검증된 buildToolToServerMap 예외 경로.
         *
         * serverA의 getToolCallbacks만 예외를 던지고, getStatus는 정상적으로 반환한다.
         * (buildServerStatusMap은 예외를 catch하지 않음)
         */
        @Test
        fun `getToolCallbacks 예외 발생 시 해당 서버를 건너뛰고 나머지를 처리해야 한다`() {
            val mcpManager = mockk<McpManager>(relaxed = true)
            val checker = McpToolAvailabilityChecker(mcpManager)

            val serverA = server("serverA")
            val serverB = server("serverB")
            val tool = toolCallback("safe-tool")

            every { mcpManager.getAllToolCallbacks() } returns listOf(tool)
            every { mcpManager.listServers() } returns listOf(serverA, serverB)
            // getStatus는 정상적으로 반환 (buildServerStatusMap은 catch 없음)
            every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
            every { mcpManager.getStatus("serverB") } returns McpServerStatus.CONNECTED
            // serverA의 getToolCallbacks만 예외 (buildToolToServerMap에서 catch됨)
            every { mcpManager.getToolCallbacks("serverA") } throws RuntimeException("콜백 조회 실패")
            every { mcpManager.getToolCallbacks("serverB") } returns listOf(tool)

            val result = checker.check(listOf("safe-tool"))

            // serverA 건너뜀 → serverB의 도구를 통해 safe-tool 발견 → CONNECTED → available
            assertTrue(result.available.contains("safe-tool")) {
                "serverA 예외로 건너뛴 후 serverB에서 도구를 찾아야 한다"
            }
            assertTrue(result.allAvailable) {
                "serverB에서 도구를 찾았으므로 allAvailable이 true여야 한다"
            }
        }

        /**
         * 서버 상태가 null인 경우 (getStatus가 null 반환) unavailable로 분류해야 한다.
         * 상태맵에 서버가 없는 엣지 케이스.
         */
        @Test
        fun `getStatus가 null을 반환하는 서버의 도구는 unavailable로 분류해야 한다`() {
            val mcpManager = mockk<McpManager>(relaxed = true)
            val checker = McpToolAvailabilityChecker(mcpManager)

            val serverA = server("serverA")
            val tool = toolCallback("orphan-tool")

            every { mcpManager.getAllToolCallbacks() } returns listOf(tool)
            every { mcpManager.listServers() } returns listOf(serverA)
            every { mcpManager.getStatus("serverA") } returns null  // 상태 없음
            every { mcpManager.getToolCallbacks("serverA") } returns listOf(tool)

            val result = checker.check(listOf("orphan-tool"))

            // null 상태는 else 브랜치 → unavailable
            assertFalse(result.allAvailable) {
                "null 상태 서버의 도구는 allAvailable이 false여야 한다"
            }
            assertEquals(listOf("orphan-tool"), result.unavailable) {
                "null 상태 서버의 도구는 unavailable로 분류되어야 한다"
            }
        }

        /**
         * 콜백이 존재하지만 어떤 서버에도 속하지 않는 경우 available로 처리해야 한다.
         * 서버가 0개인 경우 toolToServerMap이 비어있어 콜백을 찾지 못하는 케이스.
         */
        @Test
        fun `콜백이 존재하지만 서버에 속하지 않으면 available로 처리해야 한다`() {
            val mcpManager = mockk<McpManager>(relaxed = true)
            val checker = McpToolAvailabilityChecker(mcpManager)

            val tool = toolCallback("floating-tool")

            every { mcpManager.getAllToolCallbacks() } returns listOf(tool)
            every { mcpManager.listServers() } returns emptyList()  // 서버 없음

            val result = checker.check(listOf("floating-tool"))

            // 서버 정보 없이 콜백만 존재하면 available로 처리
            assertEquals(listOf("floating-tool"), result.available) {
                "서버 정보 없이 콜백만 있으면 available로 처리해야 한다"
            }
            assertTrue(result.allAvailable) {
                "available만 있으면 allAvailable이 true여야 한다"
            }
        }

        /**
         * 대량의 도구 목록 요청을 올바르게 분류해야 한다.
         */
        @Test
        fun `대량의 도구 목록을 올바르게 처리해야 한다`() {
            val mcpManager = mockk<McpManager>(relaxed = true)
            val checker = McpToolAvailabilityChecker(mcpManager)

            val tools = (1..50).map { toolCallback("tool-$it") }
            val server = server("big-server")

            every { mcpManager.getAllToolCallbacks() } returns tools
            every { mcpManager.listServers() } returns listOf(server)
            every { mcpManager.getStatus("big-server") } returns McpServerStatus.CONNECTED
            every { mcpManager.getToolCallbacks("big-server") } returns tools

            val requestedNames = (1..50).map { "tool-$it" } + listOf("missing-1", "missing-2")
            val result = checker.check(requestedNames)

            assertEquals(50, result.available.size) { "50개 도구가 available이어야 한다" }
            assertEquals(2, result.unavailable.size) { "2개 누락 도구가 unavailable이어야 한다" }
            assertTrue(result.degraded.isEmpty()) { "degraded가 비어있어야 한다" }
            assertFalse(result.allAvailable) { "missing 도구가 있으므로 allAvailable이 false여야 한다" }
        }

        /**
         * R279 reverse race fix: getAllToolCallbacks가 빈 리스트를 반환하지만 listServers
         * + per-server callbacks에서 도구가 발견되는 경우 (서버 reconnect 직후 시나리오).
         *
         * 이전(R279 fix 이전): callbackNames = getAllToolCallbacks 결과만 사용 → 빈 set →
         * 모든 도구가 unavailable로 잘못 분류.
         * R279 fix: callbackNames = union(global, per-server) → per-server에서 발견 →
         * 정상 분류 (server status에 따라 available/degraded).
         */
        @Test
        fun `R279 reverse race - getAllToolCallbacks 빈 리스트지만 per-server에서 발견 시 available`() {
            val mcpManager = mockk<McpManager>(relaxed = true)
            val checker = McpToolAvailabilityChecker(mcpManager)

            val serverA = server("serverA")
            val tool = toolCallback("reconnected-tool")

            // getAllToolCallbacks는 stale 빈 리스트 (서버 막 disconnect되었던 시점의 snapshot)
            every { mcpManager.getAllToolCallbacks() } returns emptyList()
            // listServers는 reconnect된 server를 포함
            every { mcpManager.listServers() } returns listOf(serverA)
            every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
            // per-server callbacks는 reconnect된 도구 포함
            every { mcpManager.getToolCallbacks("serverA") } returns listOf(tool)

            val result = checker.check(listOf("reconnected-tool"))

            // R279 fix: per-server에서 발견 + CONNECTED → available
            assertTrue(result.available.contains("reconnected-tool")) {
                "R279 fix: getAllToolCallbacks 빈 리스트라도 per-server에서 발견되면 available. " +
                    "actual=$result"
            }
            assertTrue(result.unavailable.isEmpty()) {
                "R279 fix: reverse race에서 unavailable로 잘못 분류되면 안 됨"
            }
        }

        /**
         * R279 forward race: per-server callbacks는 빈 리스트지만 getAllToolCallbacks에 도구가
         * 있는 경우 (서버 disconnect 직후 floating tool 시나리오, 기존 floating-tool 테스트와 자매).
         */
        @Test
        fun `R279 forward race - getAllToolCallbacks에 있지만 per-server에 없으면 floating으로 available`() {
            val mcpManager = mockk<McpManager>(relaxed = true)
            val checker = McpToolAvailabilityChecker(mcpManager)

            val serverA = server("serverA")
            val tool = toolCallback("disconnected-tool")

            // getAllToolCallbacks는 stale 도구 포함
            every { mcpManager.getAllToolCallbacks() } returns listOf(tool)
            every { mcpManager.listServers() } returns listOf(serverA)
            every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
            // per-server callbacks는 disconnect 후 빈 리스트
            every { mcpManager.getToolCallbacks("serverA") } returns emptyList()

            val result = checker.check(listOf("disconnected-tool"))

            // R279 fix: union으로 발견 + 서버 매핑 없음 → floating tool → available
            assertTrue(result.available.contains("disconnected-tool")) {
                "R279 fix: forward race에서 floating tool로 available 처리. actual=$result"
            }
        }
    }

    // ── McpHealthPinger 갭 ────────────────────────────────────────────────────

    @Nested
    inner class HealthPingerDeep {

        private val mcpManager = mockk<McpManager>(relaxed = true)

        private fun server(name: String) = McpServer(
            name = name,
            transportType = McpTransportType.SSE,
            config = mapOf("url" to "http://example.com/sse")
        )

        /**
         * start를 두 번 호출해도 두 번째는 무시되어야 한다.
         * 중복 헬스체크 루프 방지 검증.
         */
        @Test
        fun `start를 두 번 호출하면 두 번째는 무시되어야 한다`() = runTest {
            every { mcpManager.listServers() } returns emptyList()

            val pinger = McpHealthPinger(
                mcpManager = mcpManager,
                properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
                scope = backgroundScope
            )

            pinger.start()
            pinger.start() // 두 번째 start — 무시되어야 한다

            advanceTimeBy(6_000)

            // listServers가 최대 2번(각 ping 주기) 호출되어야 한다.
            // 중복 루프가 있었다면 훨씬 더 많이 호출된다
            verify(atMost = 3) { mcpManager.listServers() }
        }

        /**
         * start 없이 close를 호출해도 예외가 발생하지 않아야 한다.
         * AutoCloseable 구현 — stop을 호출해야 한다.
         */
        @Test
        fun `close 호출은 stop을 위임하여 예외 없이 완료되어야 한다`() {
            // start를 호출하지 않은 상태에서 close 호출도 안전해야 한다
            val pinger = McpHealthPinger(
                mcpManager = mcpManager,
                properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
                scope = mockk(relaxed = true)
            )

            // 예외를 던지면 안 된다
            pinger.close()

            // close 이후 listServers는 호출되지 않아야 한다
            verify(exactly = 0) { mcpManager.listServers() }
        }

        /**
         * DISCONNECTED 서버는 헬스체크 대상에서 제외해야 한다.
         * 기존 테스트는 FAILED, PENDING만 검증한다.
         */
        @Test
        fun `DISCONNECTED 상태의 서버는 헬스체크를 건너뛰어야 한다`() = runTest {
            val serverA = server("serverA")
            every { mcpManager.listServers() } returns listOf(serverA)
            every { mcpManager.getStatus("serverA") } returns McpServerStatus.DISCONNECTED

            val pinger = McpHealthPinger(
                mcpManager = mcpManager,
                properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
                scope = backgroundScope
            )
            pinger.start()

            advanceTimeBy(6_000)

            verify(exactly = 0) { mcpManager.getToolCallbacks("serverA") }
            coVerify(exactly = 0) { mcpManager.ensureConnected(any()) }
        }

        /**
         * CONNECTING 상태의 서버는 헬스체크 대상에서 제외해야 한다.
         */
        @Test
        fun `CONNECTING 상태의 서버는 헬스체크를 건너뛰어야 한다`() = runTest {
            val serverA = server("serverA")
            every { mcpManager.listServers() } returns listOf(serverA)
            every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTING

            val pinger = McpHealthPinger(
                mcpManager = mcpManager,
                properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
                scope = backgroundScope
            )
            pinger.start()

            advanceTimeBy(6_000)

            verify(exactly = 0) { mcpManager.getToolCallbacks("serverA") }
            coVerify(exactly = 0) { mcpManager.ensureConnected(any()) }
        }

        /**
         * DISABLED 상태의 서버는 헬스체크 대상에서 제외해야 한다.
         */
        @Test
        fun `DISABLED 상태의 서버는 헬스체크를 건너뛰어야 한다`() = runTest {
            val serverA = server("serverA")
            every { mcpManager.listServers() } returns listOf(serverA)
            every { mcpManager.getStatus("serverA") } returns McpServerStatus.DISABLED

            val pinger = McpHealthPinger(
                mcpManager = mcpManager,
                properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
                scope = backgroundScope
            )
            pinger.start()

            advanceTimeBy(6_000)

            verify(exactly = 0) { mcpManager.getToolCallbacks("serverA") }
            coVerify(exactly = 0) { mcpManager.ensureConnected(any()) }
        }

        /**
         * 비활성화 상태에서 stop을 호출해도 예외가 발생하지 않아야 한다.
         */
        @Test
        fun `비활성화 상태에서 stop 호출은 예외 없이 완료되어야 한다`() {
            val pinger = McpHealthPinger(
                mcpManager = mcpManager,
                properties = McpHealthProperties(enabled = false, pingIntervalSeconds = 5),
                scope = mockk(relaxed = true)
            )

            // 예외를 던지면 안 된다
            pinger.stop()
        }
    }

    // ── deduplicateCallbacksByName 갭 ─────────────────────────────────────────

    @Nested
    inner class DeduplicateCallbacksByNameDeep {

        private fun testCallback(name: String): ToolCallback {
            return object : ToolCallback {
                override val name: String = name
                override val description: String = "test-$name"
                override suspend fun call(arguments: Map<String, Any?>): Any? = "ok"
            }
        }

        /**
         * 빈 맵이 입력되면 빈 리스트를 반환해야 한다.
         */
        @Test
        fun `빈 입력이면 빈 결과를 반환해야 한다`() {
            val result = deduplicateCallbacksByName(emptyMap())
            assertTrue(result.isEmpty()) { "빈 맵은 빈 결과를 반환해야 한다" }
        }

        /**
         * 단일 서버의 콜백은 중복 제거 없이 그대로 반환되어야 한다.
         */
        @Test
        fun `단일 서버의 콜백은 원본 순서대로 반환되어야 한다`() {
            val callbacks = listOf(testCallback("a"), testCallback("b"), testCallback("c"))
            val input = mapOf("single-server" to callbacks)

            val result = deduplicateCallbacksByName(input)

            assertEquals(listOf("a", "b", "c"), result.map { it.name }) {
                "단일 서버의 콜백은 순서대로 반환되어야 한다"
            }
        }

        /**
         * 여러 서버 간 중복 도구는 알파벳 순으로 이른 서버가 우선되어야 한다.
         * WHY: 결정적(deterministic)인 우선순위 보장 — "server-a"가 "server-b"보다 먼저
         */
        @Test
        fun `중복 도구는 알파벳 순으로 이른 서버의 도구가 우선되어야 한다`() {
            val toolFromZ = testCallback("shared-tool")
            val toolFromA = testCallback("shared-tool")
            val input = mapOf(
                "z-server" to listOf(toolFromZ),
                "a-server" to listOf(toolFromA)
            )

            val result = deduplicateCallbacksByName(input)

            assertEquals(1, result.size) { "중복 도구는 하나만 남아야 한다" }
            assertSame(toolFromA, result[0]) {
                "알파벳 순으로 이른 'a-server'의 도구가 선택되어야 한다"
            }
        }

        /**
         * 중복 발견 시 onDuplicate 콜백이 올바른 인자로 호출되어야 한다.
         */
        @Test
        fun `중복 도구 발견 시 onDuplicate 콜백이 호출되어야 한다`() {
            val duplicates = mutableListOf<Triple<String, String, String>>()

            val input = mapOf(
                "a-server" to listOf(testCallback("dup")),
                "b-server" to listOf(testCallback("dup"))
            )

            deduplicateCallbacksByName(input) { toolName, keptServer, droppedServer ->
                duplicates.add(Triple(toolName, keptServer, droppedServer))
            }

            assertEquals(1, duplicates.size) { "onDuplicate가 정확히 1번 호출되어야 한다" }
            assertEquals("dup", duplicates[0].first) { "도구명이 일치해야 한다" }
            assertEquals("a-server", duplicates[0].second) { "유지 서버가 'a-server'여야 한다" }
            assertEquals("b-server", duplicates[0].third) { "무시 서버가 'b-server'여야 한다" }
        }

        /**
         * 중복 없는 경우 onDuplicate 콜백이 호출되지 않아야 한다.
         */
        @Test
        fun `중복이 없으면 onDuplicate 콜백이 호출되지 않아야 한다`() {
            val duplicateCallCount = AtomicInteger(0)

            val input = mapOf(
                "server-1" to listOf(testCallback("tool-a")),
                "server-2" to listOf(testCallback("tool-b"))
            )

            deduplicateCallbacksByName(input) { _, _, _ ->
                duplicateCallCount.incrementAndGet()
            }

            assertEquals(0, duplicateCallCount.get()) {
                "중복이 없으면 onDuplicate가 호출되지 않아야 한다"
            }
        }

        /**
         * 각 서버에서 고유한 도구들이 모두 포함되어야 한다.
         */
        @Test
        fun `중복 없는 여러 서버의 모든 도구가 결과에 포함되어야 한다`() {
            val input = mapOf(
                "c-server" to listOf(testCallback("tool-c")),
                "a-server" to listOf(testCallback("tool-a"), testCallback("tool-a2")),
                "b-server" to listOf(testCallback("tool-b"))
            )

            val result = deduplicateCallbacksByName(input)

            assertEquals(4, result.size) { "중복 없으면 4개 도구가 모두 포함되어야 한다" }
            val names = result.map { it.name }.toSet()
            assertTrue("tool-a" in names) { "tool-a가 포함되어야 한다" }
            assertTrue("tool-a2" in names) { "tool-a2가 포함되어야 한다" }
            assertTrue("tool-b" in names) { "tool-b가 포함되어야 한다" }
            assertTrue("tool-c" in names) { "tool-c가 포함되어야 한다" }
        }
    }

    // ── McpManager.ensureConnected 갭 ─────────────────────────────────────────

    @Nested
    inner class EnsureConnectedDeep {

        private fun stdioServer(name: String) = McpServer(
            name = name,
            transportType = McpTransportType.STDIO,
            config = emptyMap() // 커맨드 없음 → 즉시 실패
        )

        /**
         * CONNECTING 상태의 서버에서 ensureConnected는 false를 반환해야 한다.
         * FAILED 또는 DISCONNECTED가 아니므로 재연결을 시도하지 않는다.
         */
        @Test
        fun `CONNECTING 상태의 서버에서 ensureConnected는 false를 반환해야 한다`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(enabled = true)
            )
            manager.register(stdioServer("connecting-server"))
            // 수동으로 CONNECTING 상태 주입
            manager.statuses.put("connecting-server", McpServerStatus.CONNECTING)

            val result = manager.ensureConnected("connecting-server")

            assertFalse(result) {
                "CONNECTING 상태에서 ensureConnected는 false를 반환해야 한다"
            }
            manager.close()
        }

        /**
         * CONNECTED 상태의 서버에서 ensureConnected는 true를 반환해야 한다.
         * 이미 연결된 경우 즉시 성공을 반환한다.
         */
        @Test
        fun `CONNECTED 상태의 서버에서 ensureConnected는 true를 반환해야 한다`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(enabled = true)
            )
            manager.register(stdioServer("connected-server"))
            manager.statuses.put("connected-server", McpServerStatus.CONNECTED)

            val result = manager.ensureConnected("connected-server")

            assertTrue(result) {
                "CONNECTED 상태에서 ensureConnected는 true를 반환해야 한다"
            }
            manager.close()
        }

        /**
         * 미등록 서버에서 ensureConnected는 false를 반환해야 한다.
         * statuses에 항목이 없는 경우.
         */
        @Test
        fun `미등록 서버에서 ensureConnected는 false를 반환해야 한다`() = runBlocking {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(enabled = true)
            )

            val result = manager.ensureConnected("nonexistent-server")

            assertFalse(result) {
                "미등록 서버에서 ensureConnected는 false를 반환해야 한다"
            }
            manager.close()
        }
    }

    // ── McpSecurityConfig 기본값 갭 ───────────────────────────────────────────

    @Nested
    inner class McpSecurityConfigDeep {

        /**
         * DEFAULT_ALLOWED_STDIO_COMMANDS가 기대하는 명령어들을 포함해야 한다.
         * 실수로 기본 명령어가 제거되는 회귀를 방지한다.
         */
        @Test
        fun `DEFAULT_ALLOWED_STDIO_COMMANDS는 알려진 안전한 명령어를 포함해야 한다`() {
            val defaults = McpSecurityConfig.DEFAULT_ALLOWED_STDIO_COMMANDS

            val required = listOf("npx", "node", "python", "python3", "uvx", "uv", "docker", "deno", "bun")
            for (cmd in required) {
                assertTrue(cmd in defaults) {
                    "기본 허용 명령어 목록에 '$cmd'가 포함되어야 한다"
                }
            }
        }

        /**
         * McpSecurityConfig 기본값이 올바르게 설정되어야 한다.
         */
        @Test
        fun `McpSecurityConfig 기본값이 올바르게 설정되어야 한다`() {
            val config = McpSecurityConfig()

            assertTrue(config.allowedServerNames.isEmpty()) {
                "기본 allowedServerNames는 비어있어야 한다 (모두 허용)"
            }
            assertEquals(McpSecurityConfig.DEFAULT_MAX_TOOL_OUTPUT_LENGTH, config.maxToolOutputLength) {
                "기본 maxToolOutputLength가 ${McpSecurityConfig.DEFAULT_MAX_TOOL_OUTPUT_LENGTH}이어야 한다"
            }
            assertEquals(McpSecurityConfig.DEFAULT_ALLOWED_STDIO_COMMANDS, config.allowedStdioCommands) {
                "기본 allowedStdioCommands가 DEFAULT_ALLOWED_STDIO_COMMANDS와 일치해야 한다"
            }
        }

        /**
         * DEFAULT_MAX_TOOL_OUTPUT_LENGTH가 합리적인 값인지 확인한다.
         */
        @Test
        fun `DEFAULT_MAX_TOOL_OUTPUT_LENGTH는 합리적인 양수 값이어야 한다`() {
            assertTrue(McpSecurityConfig.DEFAULT_MAX_TOOL_OUTPUT_LENGTH > 0) {
                "DEFAULT_MAX_TOOL_OUTPUT_LENGTH는 양수여야 한다"
            }
            assertTrue(McpSecurityConfig.DEFAULT_MAX_TOOL_OUTPUT_LENGTH >= 10_000) {
                "DEFAULT_MAX_TOOL_OUTPUT_LENGTH는 최소 10,000 이상이어야 한다"
            }
        }
    }

    // ── McpManager: currentSecurityConfig 폴백 갭 ─────────────────────────────

    @Nested
    inner class SecurityConfigFallbackDeep {

        /**
         * 동적 보안 설정 제공자가 예외를 던지면 정적 설정으로 폴백해야 한다.
         * DefaultMcpManager의 currentSecurityConfig 방어 로직 검증.
         */
        @Test
        fun `동적 보안 설정 제공자 실패 시 정적 설정으로 폴백해야 한다`() {
            val staticConfig = McpSecurityConfig(
                allowedServerNames = setOf("static-server")
            )
            var failCount = 0

            val manager = DefaultMcpManager(
                securityConfig = staticConfig,
                securityConfigProvider = {
                    failCount++
                    throw RuntimeException("동적 보안 설정 로딩 실패")
                }
            )

            // 동적 제공자가 실패해도 등록이 동작해야 한다 (폴백 → 정적 설정 사용)
            manager.register(McpServer(
                name = "static-server",
                transportType = McpTransportType.STDIO,
                config = mapOf("command" to "echo")
            ))

            // 동적 제공자가 실패해도 static-server는 정적 허용 목록에 있으므로 등록되어야 한다
            assertEquals(1, manager.listServers().size) {
                "동적 제공자 실패 시 정적 설정으로 폴백하여 등록이 성공해야 한다"
            }
            assertTrue(failCount > 0) {
                "동적 제공자가 최소 1회 호출되어야 한다"
            }

            manager.close()
        }

        /**
         * 동적 보안 설정이 차단하는 서버는 폴백 후에도 차단되어야 한다.
         */
        @Test
        fun `동적 보안 설정이 정상이면 허용되지 않은 서버는 등록이 거부되어야 한다`() {
            val manager = DefaultMcpManager(
                securityConfig = McpSecurityConfig(allowedServerNames = setOf("allowed")),
                securityConfigProvider = {
                    McpSecurityConfig(allowedServerNames = setOf("allowed"))
                }
            )

            manager.register(McpServer(
                name = "blocked",
                transportType = McpTransportType.STDIO,
                config = mapOf("command" to "echo")
            ))

            assertNull(manager.getStatus("blocked")) {
                "허용되지 않은 서버는 상태가 없어야 한다"
            }
            manager.close()
        }
    }

    // ── McpManager.syncRuntimeServer 갭 ──────────────────────────────────────

    @Nested
    inner class SyncRuntimeServerDeep {

        /**
         * syncRuntimeServer는 보안 허용 목록 확인 후 기존 상태를 보존해야 한다.
         */
        @Test
        fun `syncRuntimeServer는 기존 상태를 보존하고 서버 설정만 갱신해야 한다`() {
            val manager = DefaultMcpManager(
                reconnectionProperties = McpReconnectionProperties(enabled = false)
            )

            val original = McpServer(
                name = "sync-server",
                description = "original",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://old.example.com")
            )
            manager.register(original)
            // 수동으로 CONNECTED 상태 주입
            manager.statuses.put("sync-server", McpServerStatus.CONNECTED)

            // syncRuntimeServer 호출 — 상태는 CONNECTED를 유지해야 한다
            manager.syncRuntimeServer(McpServer(
                name = "sync-server",
                description = "updated",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://new.example.com")
            ))

            assertEquals(McpServerStatus.CONNECTED, manager.getStatus("sync-server")) {
                "syncRuntimeServer는 기존 CONNECTED 상태를 보존해야 한다"
            }

            manager.close()
        }

        /**
         * syncRuntimeServer는 보안 허용 목록에 없는 서버를 거부해야 한다.
         */
        @Test
        fun `syncRuntimeServer는 보안 허용 목록에 없는 서버를 거부해야 한다`() {
            val manager = DefaultMcpManager(
                securityConfig = McpSecurityConfig(allowedServerNames = setOf("allowed-only")),
                reconnectionProperties = McpReconnectionProperties(enabled = false)
            )

            manager.syncRuntimeServer(McpServer(
                name = "blocked-sync",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://example.com")
            ))

            assertNull(manager.getStatus("blocked-sync")) {
                "허용 목록에 없는 서버는 syncRuntimeServer 후 상태가 없어야 한다"
            }

            manager.close()
        }
    }
}
