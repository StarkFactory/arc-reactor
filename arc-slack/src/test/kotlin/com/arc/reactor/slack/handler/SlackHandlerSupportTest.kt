package com.arc.reactor.slack.handler

import com.arc.reactor.mcp.McpManager
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.slack.service.SlackUserEmailResolver
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [SlackHandlerSupport]의 단위 테스트.
 *
 * buildToolSummary, resolveUserContext, resolveRequesterEmail의
 * null-guard, 예외 안전성, 정상 경로를 검증한다.
 */
class SlackHandlerSupportTest {

    /** 테스트용 SSE 기반 McpServer 팩토리. */
    private fun fakeServer(name: String) = McpServer(
        name = name,
        transportType = McpTransportType.SSE,
        config = mapOf("url" to "http://localhost:8080")
    )

    // ── buildToolSummary ────────────────────────────────────────────────────

    @Nested
    inner class BuildToolSummary {

        @Test
        fun `mcpManager가 null이면 null을 반환한다`() {
            val result = SlackHandlerSupport.buildToolSummary(null)

            result shouldBe null
        }

        @Test
        fun `연결된 서버가 없으면 null을 반환한다`() {
            val mcpManager = mockk<McpManager>()
            val server = fakeServer("jira")
            every { mcpManager.listServers() } returns listOf(server)
            every { mcpManager.getStatus("jira") } returns McpServerStatus.DISCONNECTED
            every { mcpManager.getToolCallbacks("jira") } returns emptyList()

            val result = SlackHandlerSupport.buildToolSummary(mcpManager)

            result shouldBe null
        }

        @Test
        fun `연결된 서버의 도구 요약 텍스트에 서버명과 도구명이 포함된다`() {
            val mcpManager = mockk<McpManager>()
            val server = fakeServer("jira")
            val tool = mockk<ToolCallback>()
            every { tool.name } returns "jira_create_issue"
            every { mcpManager.listServers() } returns listOf(server)
            every { mcpManager.getStatus("jira") } returns McpServerStatus.CONNECTED
            every { mcpManager.getToolCallbacks("jira") } returns listOf(tool)

            val result = SlackHandlerSupport.buildToolSummary(mcpManager)

            result shouldNotBe null
            result!!.contains("jira") shouldBe true
            result.contains("jira_create_issue") shouldBe true
        }

        @Test
        fun `도구가 없는 연결 서버는 요약에서 제외되어 null을 반환한다`() {
            val mcpManager = mockk<McpManager>()
            val server = fakeServer("confluence")
            every { mcpManager.listServers() } returns listOf(server)
            every { mcpManager.getStatus("confluence") } returns McpServerStatus.CONNECTED
            every { mcpManager.getToolCallbacks("confluence") } returns emptyList()

            val result = SlackHandlerSupport.buildToolSummary(mcpManager)

            result shouldBe null
        }

        @Test
        fun `FAILED 상태 서버는 요약에 포함되지 않는다`() {
            val mcpManager = mockk<McpManager>()
            val server = fakeServer("github")
            val tool = mockk<ToolCallback>()
            every { tool.name } returns "github_search"
            every { mcpManager.listServers() } returns listOf(server)
            every { mcpManager.getStatus("github") } returns McpServerStatus.FAILED
            every { mcpManager.getToolCallbacks("github") } returns listOf(tool)

            val result = SlackHandlerSupport.buildToolSummary(mcpManager)

            result shouldBe null
        }
    }

    // ── resolveUserContext ──────────────────────────────────────────────────

    @Nested
    inner class ResolveUserContext {

        @Test
        fun `userMemoryManager가 null이면 빈 문자열을 반환한다`() = runTest {
            val result = SlackHandlerSupport.resolveUserContext("U123", null)

            result shouldBe ""
        }

        @Test
        fun `getContextPrompt 결과를 그대로 반환한다`() = runTest {
            val manager = mockk<UserMemoryManager>()
            coEvery { manager.getContextPrompt("U123") } returns "Facts: team=backend"

            val result = SlackHandlerSupport.resolveUserContext("U123", manager)

            result shouldBe "Facts: team=backend"
        }

        @Test
        fun `getContextPrompt 예외 시 빈 문자열을 반환한다`() = runTest {
            val manager = mockk<UserMemoryManager>()
            coEvery { manager.getContextPrompt(any()) } throws RuntimeException("db error")

            val result = SlackHandlerSupport.resolveUserContext("U123", manager)

            result shouldBe ""
        }

        @Test
        fun `getContextPrompt가 빈 문자열을 반환하면 그대로 전달한다`() = runTest {
            val manager = mockk<UserMemoryManager>()
            coEvery { manager.getContextPrompt("U999") } returns ""

            val result = SlackHandlerSupport.resolveUserContext("U999", manager)

            result shouldBe ""
        }
    }

    // ── resolveRequesterEmail ───────────────────────────────────────────────

    @Nested
    inner class ResolveRequesterEmail {

        @Test
        fun `userEmailResolver가 null이면 null을 반환한다`() = runTest {
            val result = SlackHandlerSupport.resolveRequesterEmail("U123", null)

            result shouldBe null
        }

        @Test
        fun `이메일 조회 성공 시 이메일 문자열을 반환한다`() = runTest {
            val resolver = mockk<SlackUserEmailResolver>()
            coEvery { resolver.resolveEmail("U456") } returns "bob@example.com"

            val result = SlackHandlerSupport.resolveRequesterEmail("U456", resolver)

            result shouldBe "bob@example.com"
        }

        @Test
        fun `resolveEmail 예외 시 null을 반환한다`() = runTest {
            val resolver = mockk<SlackUserEmailResolver>()
            coEvery { resolver.resolveEmail(any()) } throws RuntimeException("network timeout")

            val result = SlackHandlerSupport.resolveRequesterEmail("U789", resolver)

            result shouldBe null
        }

        @Test
        fun `resolveEmail이 null을 반환하면 그대로 전달한다`() = runTest {
            val resolver = mockk<SlackUserEmailResolver>()
            coEvery { resolver.resolveEmail("U000") } returns null

            val result = SlackHandlerSupport.resolveRequesterEmail("U000", resolver)

            result shouldBe null
        }
    }
}
