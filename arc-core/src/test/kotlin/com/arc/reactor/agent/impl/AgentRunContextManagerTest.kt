package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.concurrent.CopyOnWriteArrayList

class AgentRunContextManagerTest {

    @AfterEach
    fun cleanup() {
        MDC.remove("runId")
        MDC.remove("userId")
        MDC.remove("sessionId")
    }

    @Test
    fun `should create hook context with anonymous user and copied metadata`() {
        val manager = AgentRunContextManager(runIdSupplier = { "run-1" })
        val toolsUsed = CopyOnWriteArrayList<String>()
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "hello",
            userId = null,
            metadata = mapOf("channel" to "slack", "sessionId" to 12345, "trace" to "abc")
        )

        val context = manager.open(command, toolsUsed)

        assertEquals("run-1", context.runId)
        assertEquals("anonymous", context.hookContext.userId)
        assertEquals("slack", context.hookContext.channel)
        assertEquals("abc", context.hookContext.metadata["trace"])
        assertEquals("12345", MDC.get("sessionId"))
        assertEquals("run-1", MDC.get("runId"))
        assertEquals("anonymous", MDC.get("userId"))
    }

    @Test
    fun `should clear mdc keys on close`() {
        val manager = AgentRunContextManager(runIdSupplier = { "run-1" })
        val toolsUsed = CopyOnWriteArrayList<String>()
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hello", userId = "u")

        manager.open(command, toolsUsed)
        manager.close()

        assertNull(MDC.get("runId"))
        assertNull(MDC.get("userId"))
        assertNull(MDC.get("sessionId"))
    }
}
