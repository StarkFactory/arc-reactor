package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class AgentExecutionFailureHandlerTest {

    @Test
    fun `should resolve failure call hook and record metrics`() = runBlocking {
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val errorResolver = ErrorMessageResolver { code, original ->
            "resolved:${code.name}:${original.orEmpty()}"
        }
        val handler = AgentExecutionFailureHandler(
            errorMessageResolver = errorResolver,
            hookExecutor = hookExecutor,
            agentMetrics = metrics,
            nowMs = { 1_500L }
        )
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hello")
        val resultSlot = slot<AgentResult>()

        val result = handler.handle(
            errorCode = AgentErrorCode.TIMEOUT,
            exception = IllegalStateException("boom"),
            hookContext = hookContext,
            startTime = 1_000L
        )

        assertFalse(result.success) { "Failure handler should return a failed AgentResult" }
        assertEquals("resolved:TIMEOUT:boom", result.errorMessage) {
            "Failure message should come from ErrorMessageResolver"
        }
        assertEquals(AgentErrorCode.TIMEOUT, result.errorCode) { "Failure code should be preserved" }
        assertEquals(500L, result.durationMs) { "Failure duration should be computed from startTime" }
        coVerify(exactly = 1) {
            hookExecutor.executeAfterAgentComplete(
                context = hookContext,
                response = match { !it.success && it.errorMessage == "resolved:TIMEOUT:boom" }
            )
        }
        verify(exactly = 1) { metrics.recordExecution(capture(resultSlot)) }
        assertEquals(result, resultSlot.captured) { "Recorded metrics result should match returned result" }
    }

    @Test
    fun `should continue and record metrics when after hook fails`() = runBlocking {
        val hookExecutor = mockk<HookExecutor>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        coEvery { hookExecutor.executeAfterAgentComplete(any(), any()) } throws IllegalArgumentException("hook failed")
        val handler = AgentExecutionFailureHandler(
            errorMessageResolver = ErrorMessageResolver { _, _ -> "resolved" },
            hookExecutor = hookExecutor,
            agentMetrics = metrics,
            nowMs = { 2_000L }
        )

        val result = handler.handle(
            errorCode = AgentErrorCode.UNKNOWN,
            exception = RuntimeException("boom"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hello"),
            startTime = 1_000L
        )

        assertFalse(result.success) { "Hook failure must not flip execution result to success" }
        assertEquals("resolved", result.errorMessage) { "Resolved message should still be returned" }
        assertEquals(1_000L, result.durationMs) { "Duration should still be measured when hook fails" }
        verify(exactly = 1) { metrics.recordExecution(any()) }
    }

    @Test
    fun `should rethrow cancellation from after hook`() = runBlocking {
        val hookExecutor = mockk<HookExecutor>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        coEvery { hookExecutor.executeAfterAgentComplete(any(), any()) } throws CancellationException("cancelled")
        val handler = AgentExecutionFailureHandler(
            errorMessageResolver = ErrorMessageResolver { _, _ -> "resolved" },
            hookExecutor = hookExecutor,
            agentMetrics = metrics
        )

        try {
            handler.handle(
                errorCode = AgentErrorCode.UNKNOWN,
                exception = RuntimeException("boom"),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hello"),
                startTime = 1_000L
            )
            fail("Expected CancellationException to be rethrown from failure handler")
        } catch (_: CancellationException) {
            // expected
        }

        verify(exactly = 0) { metrics.recordExecution(any()) }
    }
}
