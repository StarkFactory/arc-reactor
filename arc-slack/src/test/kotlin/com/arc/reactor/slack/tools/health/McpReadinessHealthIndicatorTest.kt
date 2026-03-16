package com.arc.reactor.slack.tools.health

import com.arc.reactor.tool.LocalTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlackToolsReadinessHealthIndicatorTest {

    private class SendMessageToolStub : LocalTool
    private class ListChannelsToolStub : LocalTool

    @Test
    fun `local tools are registered일 때 health은(는) up이다`() {
        val indicator = SlackToolsReadinessHealthIndicator(
            listOf(SendMessageToolStub(), ListChannelsToolStub())
        )

        val health = indicator.health()

        assertEquals("UP", health.status.code)
        assertEquals(2, health.details["toolCount"])
        val tools = health.details["tools"] as List<*>
        assertTrue(tools.contains("ListChannelsToolStub"))
        assertTrue(tools.contains("SendMessageToolStub"))
    }

    @Test
    fun `no local tools are registered일 때 health은(는) down이다`() {
        val indicator = SlackToolsReadinessHealthIndicator(emptyList())

        val health = indicator.health()

        assertEquals("DOWN", health.status.code)
        assertEquals("no_tools_registered", health.details["error"])
    }
}
