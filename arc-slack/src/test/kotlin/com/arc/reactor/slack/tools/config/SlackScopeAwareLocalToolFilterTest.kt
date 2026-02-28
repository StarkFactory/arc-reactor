package com.arc.reactor.slack.tools.config

import com.arc.reactor.slack.tools.tool.ReadMessagesTool
import com.arc.reactor.slack.tools.tool.SendMessageTool
import com.arc.reactor.slack.tools.usecase.ReadMessagesUseCase
import com.arc.reactor.slack.tools.usecase.SendMessageUseCase
import com.arc.reactor.tool.LocalTool
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SlackScopeAwareLocalToolFilterTest {

    private class NonSlackTool : LocalTool

    @Test
    fun `keeps all tools when scope-aware exposure is disabled`() {
        val resolver = ToolExposureResolver(
            properties = SlackToolsProperties(
                enabled = true,
                botToken = "xoxb-test",
                toolExposure = ToolExposureProperties(scopeAwareEnabled = false)
            ),
            slackScopeProvider = object : SlackScopeProvider {
                override fun resolveGrantedScopes(): Set<String> = emptySet()
            }
        )
        val filter = createSlackScopeAwareLocalToolFilter(resolver)
        val tools = listOf(
            SendMessageTool(mockk<SendMessageUseCase>()),
            ReadMessagesTool(mockk<ReadMessagesUseCase>()),
            NonSlackTool()
        )

        val filtered = filter.filter(tools)

        assertEquals(3, filtered.size)
    }

    @Test
    fun `filters slack tools by granted scopes`() {
        val resolver = ToolExposureResolver(
            properties = SlackToolsProperties(
                enabled = true,
                botToken = "xoxb-test",
                toolExposure = ToolExposureProperties(scopeAwareEnabled = true)
            ),
            slackScopeProvider = object : SlackScopeProvider {
                override fun resolveGrantedScopes(): Set<String> = setOf("chat:write")
            }
        )
        val filter = createSlackScopeAwareLocalToolFilter(resolver)
        val tools = listOf(
            SendMessageTool(mockk<SendMessageUseCase>()),
            ReadMessagesTool(mockk<ReadMessagesUseCase>()),
            NonSlackTool()
        )

        val filtered = filter.filter(tools)

        assertEquals(2, filtered.size)
        assertEquals(
            listOf("SendMessageTool", "NonSlackTool"),
            filtered.map { it::class.simpleName }
        )
    }

    @Test
    fun `keeps only non-slack tools on empty scopes when fail-open is disabled`() {
        val resolver = ToolExposureResolver(
            properties = SlackToolsProperties(
                enabled = true,
                botToken = "xoxb-test",
                toolExposure = ToolExposureProperties(
                    scopeAwareEnabled = true,
                    failOpenOnScopeResolutionError = false
                )
            ),
            slackScopeProvider = object : SlackScopeProvider {
                override fun resolveGrantedScopes(): Set<String> = emptySet()
            }
        )
        val filter = createSlackScopeAwareLocalToolFilter(resolver)
        val tools = listOf(
            SendMessageTool(mockk<SendMessageUseCase>()),
            ReadMessagesTool(mockk<ReadMessagesUseCase>()),
            NonSlackTool()
        )

        val filtered = filter.filter(tools)

        assertEquals(1, filtered.size)
        assertEquals("NonSlackTool", filtered.first()::class.simpleName)
    }
}
