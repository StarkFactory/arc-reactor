package com.arc.reactor.slack.tools.config

import com.arc.reactor.slack.tools.tool.ReadMessagesTool
import com.arc.reactor.slack.tools.tool.SendMessageTool
import com.arc.reactor.slack.tools.tool.CreateCanvasTool
import com.arc.reactor.slack.tools.usecase.ReadMessagesUseCase
import com.arc.reactor.slack.tools.usecase.SendMessageUseCase
import com.arc.reactor.slack.tools.usecase.CreateCanvasUseCase
import com.arc.reactor.tool.LocalTool
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SlackScopeAwareLocalToolFilterTest {

    private class NonSlackTool : LocalTool

    @Test
    fun `keeps all tools when scope-aware exposure은(는) disabled이다`() {
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
        val properties = SlackToolsProperties(
            enabled = true,
            botToken = "xoxb-test",
            toolExposure = ToolExposureProperties(scopeAwareEnabled = false)
        )
        val filter = createSlackScopeAwareLocalToolFilter(properties, resolver)
        val tools = listOf(
            SendMessageTool(mockk<SendMessageUseCase>()),
            ReadMessagesTool(mockk<ReadMessagesUseCase>()),
            NonSlackTool()
        )

        val filtered = filter.filter(tools)

        assertEquals(3, filtered.size)
    }

    @Test
    fun `slack tools by granted scopes를 필터링한다`() {
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
        val properties = SlackToolsProperties(
            enabled = true,
            botToken = "xoxb-test",
            toolExposure = ToolExposureProperties(scopeAwareEnabled = true)
        )
        val filter = createSlackScopeAwareLocalToolFilter(properties, resolver)
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
    fun `keeps only non-slack tools on empty scopes when fail-open은(는) disabled이다`() {
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
        val properties = SlackToolsProperties(
            enabled = true,
            botToken = "xoxb-test",
            toolExposure = ToolExposureProperties(
                scopeAwareEnabled = true,
                failOpenOnScopeResolutionError = false
            )
        )
        val filter = createSlackScopeAwareLocalToolFilter(properties, resolver)
        val tools = listOf(
            SendMessageTool(mockk<SendMessageUseCase>()),
            ReadMessagesTool(mockk<ReadMessagesUseCase>()),
            NonSlackTool()
        )

        val filtered = filter.filter(tools)

        assertEquals(1, filtered.size)
        assertEquals("NonSlackTool", filtered.first()::class.simpleName)
    }

    @Test
    fun `read tool은(는) exposed by groups history in include private mode이다`() {
        val properties = SlackToolsProperties(
            enabled = true,
            botToken = "xoxb-test",
            toolExposure = ToolExposureProperties(
                scopeAwareEnabled = true,
                conversationScopeMode = ConversationScopeMode.INCLUDE_PRIVATE_AND_DM
            )
        )
        val resolver = ToolExposureResolver(
            properties = properties,
            slackScopeProvider = object : SlackScopeProvider {
                override fun resolveGrantedScopes(): Set<String> = setOf("groups:history")
            }
        )
        val filter = createSlackScopeAwareLocalToolFilter(properties, resolver)
        val tools = listOf(
            ReadMessagesTool(mockk<ReadMessagesUseCase>()),
            NonSlackTool()
        )

        val filtered = filter.filter(tools)

        assertEquals(2, filtered.size)
        assertEquals(listOf("ReadMessagesTool", "NonSlackTool"), filtered.map { it::class.simpleName })
    }

    @Test
    fun `canvases scope is missing일 때 canvas tool은(는) filtered out이다`() {
        val properties = SlackToolsProperties(
            enabled = true,
            botToken = "xoxb-test",
            toolExposure = ToolExposureProperties(
                scopeAwareEnabled = true,
                failOpenOnScopeResolutionError = false
            )
        )
        val resolver = ToolExposureResolver(
            properties = properties,
            slackScopeProvider = object : SlackScopeProvider {
                override fun resolveGrantedScopes(): Set<String> = setOf("chat:write")
            }
        )
        val filter = createSlackScopeAwareLocalToolFilter(properties, resolver)
        val tools = listOf(
            CreateCanvasTool(mockk<CreateCanvasUseCase>()),
            NonSlackTool()
        )

        val filtered = filter.filter(tools)

        assertEquals(1, filtered.size)
        assertEquals("NonSlackTool", filtered.first()::class.simpleName)
    }
}
