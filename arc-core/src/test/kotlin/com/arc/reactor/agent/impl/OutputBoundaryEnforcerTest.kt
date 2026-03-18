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

/**
 * OutputBoundaryEnforcer에 대한 테스트.
 *
 * 출력 경계 적용 로직을 검증합니다.
 */
class OutputBoundaryEnforcerTest {

    @Test
    fun `output exceeds max chars일 때 truncate content해야 한다`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(outputMaxChars = 5),
            agentMetrics = metrics
        )

        val result = enforcer.enforceOutputBoundaries(
            result = AgentResult.success(content = "1234567"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            metadata = emptyMap(),
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertEquals("12345\n\n[Response truncated]", result?.content)
        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_long", "truncate", 5, 7, any()) }
    }

    @Test
    fun `output is too short and mode is FAIL일 때 return null해야 한다`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(outputMinChars = 10, outputMinViolationMode = OutputMinViolationMode.FAIL),
            agentMetrics = metrics
        )

        val result = enforcer.enforceOutputBoundaries(
            result = AgentResult.success(content = "short"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            metadata = emptyMap(),
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertNull(result, "Enforcer should return null (drop response) when mode=FAIL and content is too short")
        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_short", "fail", 10, 5, any()) }
    }

    @Test
    fun `mode is RETRY_ONCE and retry is long enough일 때 use retried content해야 한다`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(outputMinChars = 10, outputMinViolationMode = OutputMinViolationMode.RETRY_ONCE),
            agentMetrics = metrics
        )

        val result = enforcer.enforceOutputBoundaries(
            result = AgentResult.success(content = "short"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            metadata = emptyMap(),
            attemptLongerResponse = { _, _, _ -> "long enough response" }
        )

        assertTrue(result?.success == true, "Retry result should be successful when retry content meets minimum length")
        assertEquals("long enough response", result?.content)
        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_short", "retry_once", 10, 5, any()) }
    }

    @Test
    fun `RETRY_ONCE retry result is still short일 때 keep original content해야 한다`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(outputMinChars = 10, outputMinViolationMode = OutputMinViolationMode.RETRY_ONCE),
            agentMetrics = metrics
        )

        val result = enforcer.enforceOutputBoundaries(
            result = AgentResult.success(content = "short"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            metadata = emptyMap(),
            attemptLongerResponse = { _, _, _ -> "tiny" }
        )

        assertEquals("short", result?.content)
    }
}
