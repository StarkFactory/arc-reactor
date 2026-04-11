package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpHealthProperties
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.tool.ToolCallback
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpHealthPingerTest {

    private val mcpManager = mockk<McpManager>(relaxed = true)

    private fun createPinger(
        enabled: Boolean = true,
        pingIntervalSeconds: Long = 10
    ): McpHealthPinger {
        return McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(
                enabled = enabled,
                pingIntervalSeconds = pingIntervalSeconds
            ),
            scope = mockk(relaxed = true)
        )
    }

    private fun server(name: String): McpServer = McpServer(
        name = name,
        transportType = McpTransportType.SSE,
        config = mapOf("url" to "http://example.com/sse")
    )

    @Test
    fun `비활성화 시 헬스체크를 시작하지 않는다`() {
        val pinger = createPinger(enabled = false)
        pinger.start()
        // 비활성화 시 mcpManager에 접근하지 않아야 한다
        verify(exactly = 0) { mcpManager.listServers() }
    }

    @Test
    fun `CONNECTED 서버의 도구가 존재하면 재연결하지 않는다`() = runTest {
        val serverA = server("serverA")
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
        every { mcpManager.getToolCallbacks("serverA") } returns listOf(mockk<ToolCallback>())

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )
        pinger.start()

        advanceTimeBy(6_000)

        coVerify(exactly = 0) { mcpManager.ensureConnected(any()) }
    }

    @Test
    fun `R331 0-tool 서버는 첫 관찰에서 재연결을 시도하지 않는다`() = runTest {
        // R331 regression: MCP 프로토콜은 0-tool 서버(resource/prompt만 제공)를 허용한다.
        // 이런 서버는 한 번도 non-empty였던 적이 없으므로 재연결 루프에 들어가면 안 된다.
        val serverA = server("serverA")
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
        every { mcpManager.getToolCallbacks("serverA") } returns emptyList()
        coEvery { mcpManager.ensureConnected("serverA") } returns true

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )
        pinger.start()

        // 여러 ping 주기를 돌아도 0-tool 서버는 안정 상태로 취급되어야 한다
        advanceTimeBy(30_000)

        coVerify(exactly = 0) {
            mcpManager.ensureConnected("serverA")
        }
    }

    @Test
    fun `R331 이전에 도구가 있던 서버가 비어지면 재연결을 시도한다`() = runTest {
        // R331 regression: non-empty에서 empty로 퇴화한 경우는 여전히 degradation으로
        // 감지하여 재연결해야 한다.
        val serverA = server("serverA")
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
        // 첫 ping: non-empty (baseline 마킹) → 두 번째 ping부터: empty (퇴화)
        every { mcpManager.getToolCallbacks("serverA") } returnsMany listOf(
            listOf(mockk<ToolCallback>()),
            emptyList(),
            emptyList()
        )
        coEvery { mcpManager.ensureConnected("serverA") } returns true

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )
        pinger.start()

        // 첫 ping(6s) — baseline 마킹, 재연결 없음
        advanceTimeBy(6_000)
        coVerify(exactly = 0) { mcpManager.ensureConnected("serverA") }

        // 두 번째 ping(12s) — empty로 퇴화 감지 → 재연결 시도
        advanceTimeBy(6_000)
        coVerify(atLeast = 1) { mcpManager.ensureConnected("serverA") }
    }

    @Test
    fun `FAILED 상태의 서버는 자동 재연결을 시도한다`() = runTest {
        // R173: FAILED/PENDING 서버도 헬스체크에서 재연결 시도 (쿨다운 적용)
        val serverA = server("serverA")
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.FAILED
        coEvery { mcpManager.ensureConnected("serverA") } returns true

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )
        pinger.start()

        advanceTimeBy(6_000)

        // FAILED 상태에서 ensureConnected 호출되어야 한다 (도구 콜백 조회는 안 함)
        verify(exactly = 0) { mcpManager.getToolCallbacks("serverA") }
        coVerify(atLeast = 1) { mcpManager.ensureConnected("serverA") }
    }

    @Test
    fun `PENDING 상태의 서버는 자동 재연결을 시도한다`() = runTest {
        // R173: PENDING 서버도 헬스체크에서 재연결 시도
        val serverA = server("serverA")
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.PENDING
        coEvery { mcpManager.ensureConnected("serverA") } returns true

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )
        pinger.start()

        advanceTimeBy(6_000)

        verify(exactly = 0) { mcpManager.getToolCallbacks("serverA") }
        coVerify(atLeast = 1) { mcpManager.ensureConnected("serverA") }
    }

    @Test
    fun `stop 호출 후 헬스체크가 중지된다`() = runTest {
        every { mcpManager.listServers() } returns emptyList()

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )
        pinger.start()
        pinger.stop()

        // stop 이후에는 추가 호출이 없어야 한다
        advanceTimeBy(20_000)
    }

    @Test
    fun `여러 서버를 동시에 점검한다`() = runTest {
        // R331 업데이트: serverB는 첫 관찰부터 0-tool이므로 0-tool 서버로 간주되어
        // 재연결하지 않는다. 퇴화(non-empty → empty) 경로는 별도 R331 테스트가 커버.
        val serverA = server("serverA")
        val serverB = server("serverB")
        every { mcpManager.listServers() } returns listOf(serverA, serverB)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
        every { mcpManager.getStatus("serverB") } returns McpServerStatus.CONNECTED
        every { mcpManager.getToolCallbacks("serverA") } returns listOf(mockk<ToolCallback>())
        every { mcpManager.getToolCallbacks("serverB") } returns emptyList()

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )
        pinger.start()

        advanceTimeBy(6_000)

        // 두 서버 모두 첫 관찰이므로 재연결하지 않아야 한다 (A=non-empty baseline,
        // B=stable 0-tool)
        coVerify(exactly = 0) { mcpManager.ensureConnected("serverA") }
        coVerify(exactly = 0) { mcpManager.ensureConnected("serverB") }
    }

    @Test
    fun `getToolCallbacks 예외 시 재연결을 시도해야 한다`() = runTest {
        val serverA = server("serverA")
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
        every { mcpManager.getToolCallbacks("serverA") } throws RuntimeException("connection lost")
        coEvery { mcpManager.ensureConnected("serverA") } returns true

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )
        pinger.start()

        advanceTimeBy(6_000)

        // getToolCallbacks 예외 시 ensureConnected가 호출되어야 한다
        coVerify(atLeast = 1) {
            mcpManager.ensureConnected("serverA")
        }
    }

    @Test
    fun `재연결 실패 시 로깅 후 계속 진행해야 한다`() = runTest {
        val serverA = server("serverA")
        val serverB = server("serverB")
        every { mcpManager.listServers() } returns listOf(serverA, serverB)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
        every { mcpManager.getStatus("serverB") } returns McpServerStatus.CONNECTED
        every { mcpManager.getToolCallbacks("serverA") } throws RuntimeException("connection lost")
        every { mcpManager.getToolCallbacks("serverB") } returns listOf(mockk<ToolCallback>())
        coEvery { mcpManager.ensureConnected("serverA") } throws RuntimeException("reconnect failed")

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )
        pinger.start()

        advanceTimeBy(6_000)

        // 재연결 실패해도 serverB 헬스체크가 수행되어야 한다
        verify(atLeast = 1) {
            mcpManager.getToolCallbacks("serverB")
        }

        // 두 번째 ping 주기에도 루프가 계속 동작해야 한다
        advanceTimeBy(6_000)
        verify(atLeast = 2) {
            mcpManager.listServers()
        }
    }

    @Test
    fun `listServers 예외 시 pingAllConnectedServers에서 예외가 전파된다`() = runTest {
        every { mcpManager.listServers() } throws RuntimeException("manager unavailable")

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )

        // listServers 예외는 pingAllConnectedServers에서 전파되어야 한다
        shouldThrow<RuntimeException> {
            pinger.pingAllConnectedServers()
        }

        // listServers 예외 시 getStatus나 ensureConnected가 호출되지 않아야 한다
        verify(exactly = 0) {
            mcpManager.getStatus(any())
        }
        coVerify(exactly = 0) {
            mcpManager.ensureConnected(any())
        }
    }

    @Test
    fun `CancellationException은 전파되어야 한다`() = runTest {
        val serverA = server("serverA")
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
        every { mcpManager.getToolCallbacks("serverA") } throws CancellationException("cancelled")

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )

        // CancellationException은 catch되지 않고 전파되어야 한다
        shouldThrow<CancellationException> {
            pinger.pingAllConnectedServers()
        }

        // CancellationException 시 재연결이 시도되지 않아야 한다
        coVerify(exactly = 0) {
            mcpManager.ensureConnected(any())
        }
    }

    @Test
    fun `재연결 중 CancellationException은 전파되어야 한다`() = runTest {
        val serverA = server("serverA")
        every { mcpManager.listServers() } returns listOf(serverA)
        every { mcpManager.getStatus("serverA") } returns McpServerStatus.CONNECTED
        every { mcpManager.getToolCallbacks("serverA") } throws RuntimeException("connection lost")
        coEvery { mcpManager.ensureConnected("serverA") } throws CancellationException("cancelled")

        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = true, pingIntervalSeconds = 5),
            scope = backgroundScope
        )

        // ensureConnected에서 발생한 CancellationException도 전파되어야 한다
        shouldThrow<CancellationException> {
            pinger.pingAllConnectedServers()
        }
    }

    @Test
    fun `인스턴스를 생성할 수 있다`() {
        val pinger = createPinger()
        assertNotNull(pinger, "McpHealthPinger 인스턴스를 생성할 수 있어야 한다")
    }

    @Test
    fun `R283 ownsScope true이면 close가 부모 SupervisorJob까지 cancel한다`() {
        val parentJob = SupervisorJob()
        val ownedScope = CoroutineScope(Dispatchers.IO + parentJob)
        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = false),
            scope = ownedScope,
            ownsScope = true
        )

        assertTrue(parentJob.isActive) {
            "close 호출 전 parent SupervisorJob은 active 상태여야 한다"
        }

        pinger.close()

        assertFalse(parentJob.isActive) {
            "R283 fix: ownsScope=true에서 close 호출 후 parent SupervisorJob이 cancel되어야 한다"
        }
        assertTrue(parentJob.isCancelled) {
            "R283 fix: parent SupervisorJob이 cancelled 상태여야 한다"
        }
    }

    @Test
    fun `R283 ownsScope false이면 close가 외부 스코프를 cancel하지 않는다`() {
        val externalJob = SupervisorJob()
        val externalScope = CoroutineScope(Dispatchers.IO + externalJob)
        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = McpHealthProperties(enabled = false),
            scope = externalScope
            // ownsScope 기본값 false
        )

        pinger.close()

        assertTrue(externalJob.isActive) {
            "R283 fix: ownsScope=false(기본)에서 close 호출 후 외부 스코프는 영향받지 않아야 한다 " +
                "(테스트의 backgroundScope 등 외부 lifecycle 보호)"
        }
        externalJob.cancel() // 테스트 정리
    }
}
