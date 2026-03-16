package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import kotlinx.coroutines.test.runTest
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
        MDC.remove("userEmail")
        MDC.remove("sessionId")
    }

    @Test
    fun `anonymous user and copied metadata로 create hook context해야 한다`() = runTest {
        val manager = AgentRunContextManager(runIdSupplier = { "run-1" })
        val toolsUsed = CopyOnWriteArrayList<String>()
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "hello",
            userId = null,
            metadata = mapOf(
                "channel" to "slack",
                "sessionId" to 12345,
                "trace" to "abc",
                "requesterEmail" to "alice@example.com"
            )
        )

        val context = manager.open(command, toolsUsed)

        assertEquals("run-1", context.runId, "runId should match supplier value")
        assertEquals("anonymous", context.hookContext.userId, "null userId should resolve to anonymous")
        assertEquals("slack", context.hookContext.channel, "channel should be set from metadata")
        assertEquals("alice@example.com", context.hookContext.userEmail, "userEmail should be resolved from requesterEmail")
        assertEquals("abc", context.hookContext.metadata["trace"], "metadata trace key should be copied")
        assertEquals("12345", MDC.get("sessionId"), "MDC sessionId should be set")
        assertEquals("alice@example.com", MDC.get("userEmail"), "MDC userEmail should be set")
        assertEquals("run-1", MDC.get("runId"), "MDC runId should be set")
        assertEquals("anonymous", MDC.get("userId"), "MDC userId should be set")
    }

    @Test
    fun `email is unavailable일 때 use accountId해야 한다`() = runTest {
        val manager = AgentRunContextManager(runIdSupplier = { "run-2" })
        val toolsUsed = CopyOnWriteArrayList<String>()
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "hello",
            userId = null,
            metadata = mapOf(
                "channel" to "web",
                "accountId" to "acct-777"
            )
        )

        val context = manager.open(command, toolsUsed)

        assertEquals("run-2", context.runId, "runId should match supplier value")
        assertEquals("acct-777", context.hookContext.userEmail, "accountId should be used as email fallback")
    }

    @Test
    fun `clear mdc keys on close해야 한다`() = runTest {
        val manager = AgentRunContextManager(runIdSupplier = { "run-1" })
        val toolsUsed = CopyOnWriteArrayList<String>()
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hello", userId = "u")

        manager.open(command, toolsUsed)
        manager.close()

        assertNull(MDC.get("runId"), "MDC runId should be cleared after close()")
        assertNull(MDC.get("userId"), "MDC userId should be cleared after close()")
        assertNull(MDC.get("userEmail"), "MDC userEmail should be cleared after close()")
        assertNull(MDC.get("sessionId"), "MDC sessionId should be cleared after close()")
    }
}
