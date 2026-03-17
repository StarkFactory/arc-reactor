package com.arc.reactor.controller

import com.arc.reactor.a2a.AgentCapability
import com.arc.reactor.a2a.AgentCard
import com.arc.reactor.a2a.AgentCardProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AgentCardController에 대한 테스트.
 *
 * A2A 에이전트 카드 엔드포인트의 동작을 검증한다.
 */
class AgentCardControllerTest {

    private val sampleCard = AgentCard(
        name = "test-agent",
        version = "1.0.0",
        description = "Test Agent",
        capabilities = listOf(
            AgentCapability(
                name = "search_tool",
                description = "Search tool",
                inputSchema = """{"type":"object"}"""
            ),
            AgentCapability(
                name = "persona:Default",
                description = "Default persona"
            )
        )
    )

    @Test
    fun `에이전트 카드를 반환해야 한다`() {
        val provider = mockk<AgentCardProvider>()
        every { provider.generate() } returns sampleCard

        val controller = AgentCardController(provider)
        val result = controller.getAgentCard()

        assertEquals("test-agent", result.name) { "에이전트 이름이 일치해야 한다" }
        assertEquals("1.0.0", result.version) { "에이전트 버전이 일치해야 한다" }
        assertEquals("Test Agent", result.description) { "에이전트 설명이 일치해야 한다" }
        assertEquals(2, result.capabilities.size) { "능력 목록 크기가 일치해야 한다" }
    }

    @Test
    fun `능력 목록이 비어있어도 정상 반환해야 한다`() {
        val emptyCard = AgentCard(
            name = "empty-agent",
            version = "0.1.0",
            description = "Empty Agent",
            capabilities = emptyList()
        )
        val provider = mockk<AgentCardProvider>()
        every { provider.generate() } returns emptyCard

        val controller = AgentCardController(provider)
        val result = controller.getAgentCard()

        assertEquals("empty-agent", result.name) { "에이전트 이름이 일치해야 한다" }
        assertTrue(result.capabilities.isEmpty()) { "빈 능력 목록이 반환되어야 한다" }
    }

    @Test
    fun `기본 입출력 형식이 포함되어야 한다`() {
        val provider = mockk<AgentCardProvider>()
        every { provider.generate() } returns sampleCard

        val controller = AgentCardController(provider)
        val result = controller.getAgentCard()

        assertTrue(result.supportedInputFormats.contains("text")) {
            "입력 형식에 text가 포함되어야 한다"
        }
        assertTrue(result.supportedOutputFormats.contains("json")) {
            "출력 형식에 json이 포함되어야 한다"
        }
    }
}
