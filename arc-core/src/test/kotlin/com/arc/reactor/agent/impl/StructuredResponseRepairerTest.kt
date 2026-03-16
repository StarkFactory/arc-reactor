package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.TokenUsage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec

/**
 * StructuredResponseRepairer에 대한 테스트.
 *
 * 구조화된 응답 복구 로직을 검증합니다.
 */
class StructuredResponseRepairerTest {

    @Test
    fun `text format에 대해 raw success를 반환한다`() = runBlocking {
        val repairer = StructuredResponseRepairer(
            errorMessageResolver = DefaultErrorMessageResolver(),
            resolveChatClient = { mockk() }
        )
        val usage = TokenUsage(promptTokens = 10, completionTokens = 20)

        val result = repairer.validateAndRepair(
            rawContent = "plain text",
            format = ResponseFormat.TEXT,
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello"),
            tokenUsage = usage,
            toolsUsed = listOf("tool-a")
        )

        assertTrue(result.success, "TEXT format should always pass validation and return success")
        assertEquals("plain text", result.content)
        assertEquals(listOf("tool-a"), result.toolsUsed)
        assertEquals(usage, result.tokenUsage)
    }

    @Test
    fun `invalid json is fixed by llm일 때 repaired success를 반환한다`() = runBlocking {
        val chatClient = mockk<ChatClient>()
        val requestSpec = mockk<ChatClientRequestSpec>(relaxed = true)
        val responseSpec = mockk<CallResponseSpec>()
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns responseSpec
        every { responseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("""{"ok":true}""")

        val repairer = StructuredResponseRepairer(
            errorMessageResolver = DefaultErrorMessageResolver(),
            resolveChatClient = { chatClient }
        )

        val result = repairer.validateAndRepair(
            rawContent = "{invalid",
            format = ResponseFormat.JSON,
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello"),
            tokenUsage = null,
            toolsUsed = emptyList()
        )

        assertTrue(result.success, "LLM-repaired JSON should produce a successful result")
        assertEquals("""{"ok":true}""", result.content)
    }

    @Test
    fun `repair does not produce valid format일 때 invalid response failure를 반환한다`() = runBlocking {
        val chatClient = mockk<ChatClient>()
        val requestSpec = mockk<ChatClientRequestSpec>(relaxed = true)
        val responseSpec = mockk<CallResponseSpec>()
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns responseSpec
        every { responseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("still invalid")

        val repairer = StructuredResponseRepairer(
            errorMessageResolver = DefaultErrorMessageResolver(),
            resolveChatClient = { chatClient }
        )

        val result = repairer.validateAndRepair(
            rawContent = "{invalid",
            format = ResponseFormat.JSON,
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello"),
            tokenUsage = null,
            toolsUsed = emptyList()
        )

        assertFalse(result.success, "Should fail when LLM repair does not produce valid JSON")
        assertEquals(AgentErrorCode.INVALID_RESPONSE, result.errorCode)
    }

    @Test
    fun `repair동안 rethrows cancellation exception`() {
        val chatClient = mockk<ChatClient>()
        val requestSpec = mockk<ChatClientRequestSpec>(relaxed = true)
        val responseSpec = mockk<CallResponseSpec>()
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns responseSpec
        every { responseSpec.chatResponse() } throws CancellationException("cancelled")

        val repairer = StructuredResponseRepairer(
            errorMessageResolver = DefaultErrorMessageResolver(),
            resolveChatClient = { chatClient }
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                repairer.validateAndRepair(
                    rawContent = "{invalid",
                    format = ResponseFormat.JSON,
                    command = AgentCommand(systemPrompt = "sys", userPrompt = "hello"),
                    tokenUsage = null,
                    toolsUsed = emptyList()
                )
            }
        }
    }
}
