package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutputBoundaryEnforcerTest {

    @Test
    fun `should truncate content when output exceeds max chars`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(outputMaxChars = 5),
            agentMetrics = metrics
        )

        val result = enforcer.apply(
            result = AgentResult.success(content = "1234567"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertEquals("12345\n\n[Response truncated]", result?.content)
        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_long", "truncate", 5, 7) }
    }

    @Test
    fun `should return null when output is too short and mode is FAIL`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(outputMinChars = 10, outputMinViolationMode = OutputMinViolationMode.FAIL),
            agentMetrics = metrics
        )

        val result = enforcer.apply(
            result = AgentResult.success(content = "short"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertNull(result, "Enforcer should return null (drop response) when mode=FAIL and content is too short")
        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_short", "fail", 10, 5) }
    }

    @Test
    fun `should use retried content when mode is RETRY_ONCE and retry is long enough`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(outputMinChars = 10, outputMinViolationMode = OutputMinViolationMode.RETRY_ONCE),
            agentMetrics = metrics
        )

        val result = enforcer.apply(
            result = AgentResult.success(content = "short"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            attemptLongerResponse = { _, _, _ -> "long enough response" }
        )

        assertTrue(result?.success == true, "Retry result should be successful when retry content meets minimum length")
        assertEquals("long enough response", result?.content)
        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_short", "retry_once", 10, 5) }
    }

    @Test
    fun `should keep original content when RETRY_ONCE retry result is still short`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(outputMinChars = 10, outputMinViolationMode = OutputMinViolationMode.RETRY_ONCE),
            agentMetrics = metrics
        )

        val result = enforcer.apply(
            result = AgentResult.success(content = "short"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            attemptLongerResponse = { _, _, _ -> "tiny" }
        )

        assertEquals("short", result?.content)
    }
}
