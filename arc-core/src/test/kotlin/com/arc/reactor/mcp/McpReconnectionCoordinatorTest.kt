package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServerStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
/**
 * MCP 재연결 코디네이터에 대한 테스트.
 *
 * MCP 서버 재연결 조정 로직을 검증합니다.
 */
class McpReconnectionCoordinatorTest {

    @Test
    fun `cancels scheduled reconnect before it runs를 비운다`() = runTest {
        var reconnectAttempts = 0
        var status = McpServerStatus.FAILED
        val coordinator = McpReconnectionCoordinator(
            scope = backgroundScope,
            properties = McpReconnectionProperties(
                enabled = true,
                maxAttempts = 3,
                initialDelayMs = 1_000,
                multiplier = 1.0,
                maxDelayMs = 1_000
            ),
            statusProvider = { status },
            serverExists = { true },
            reconnectAction = {
                reconnectAttempts += 1
                false
            }
        )

        coordinator.schedule("atlassian")
        coordinator.clear("atlassian")

        advanceTimeBy(5_000)

        assertEquals(0, reconnectAttempts)
    }

    @Test
    fun `allows a fresh reconnect schedule without leaking the old job를 비운다`() = runTest {
        var reconnectAttempts = 0
        var status = McpServerStatus.FAILED
        val coordinator = McpReconnectionCoordinator(
            scope = backgroundScope,
            properties = McpReconnectionProperties(
                enabled = true,
                maxAttempts = 1,
                initialDelayMs = 1_000,
                multiplier = 1.0,
                maxDelayMs = 1_000
            ),
            statusProvider = { status },
            serverExists = { true },
            reconnectAction = {
                reconnectAttempts += 1
                false
            }
        )

        coordinator.schedule("atlassian")
        coordinator.clear("atlassian")
        coordinator.schedule("atlassian")

        advanceTimeBy(2_000)

        assertEquals(1, reconnectAttempts)
    }
}
