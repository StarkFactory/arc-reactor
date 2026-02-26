package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.hook.model.HookContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class StreamingFlowLifecycleCoordinatorTest {

    @Test
    fun `should finalize streaming and record success metrics`() = runBlocking {
        val completionFinalizer = mockk<StreamingCompletionFinalizer>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        coEvery {
            completionFinalizer.finalize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Unit
        var closeCalled = false
        val coordinator = StreamingFlowLifecycleCoordinator(
            streamingCompletionFinalizer = completionFinalizer,
            agentMetrics = metrics,
            closeRunContext = { closeCalled = true },
            nowMs = { 1_500L }
        )
        val state = StreamingExecutionState(
            streamSuccess = true,
            streamStarted = true,
            collectedContent = StringBuilder("stream-body"),
            lastIterationContent = StringBuilder("last-iteration")
        )
        val resultSlot = slot<AgentResult>()

        coordinator.finalize(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = listOf("search"),
            state = state,
            startTime = 1_000L,
            emit = {}
        )

        coVerify(exactly = 1) {
            completionFinalizer.finalize(
                command = any(),
                hookContext = any(),
                streamStarted = true,
                streamSuccess = true,
                collectedContent = "stream-body",
                lastIterationContent = "last-iteration",
                streamErrorMessage = null,
                streamErrorCode = null,
                toolsUsed = listOf("search"),
                startTime = 1_000L,
                emit = any()
            )
        }
        verify(exactly = 1) { metrics.recordStreamingExecution(capture(resultSlot)) }
        assertTrue(resultSlot.captured.success) { "Streaming metrics should be recorded as success" }
        assertEquals("stream-body", resultSlot.captured.content) { "Metrics content should match stream output" }
        assertEquals(500L, resultSlot.captured.durationMs) { "Duration should be calculated from start time" }
        assertEquals(listOf("search"), resultSlot.captured.toolsUsed) { "Tools used should be preserved in metrics" }
        assertTrue(closeCalled) { "Run context must be closed after streaming finalization" }
    }

    @Test
    fun `should record failure metrics with fallback UNKNOWN code`() = runBlocking {
        val completionFinalizer = mockk<StreamingCompletionFinalizer>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        coEvery {
            completionFinalizer.finalize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Unit
        val coordinator = StreamingFlowLifecycleCoordinator(
            streamingCompletionFinalizer = completionFinalizer,
            agentMetrics = metrics,
            closeRunContext = {},
            nowMs = { 2_000L }
        )
        val state = StreamingExecutionState(
            streamSuccess = false,
            streamErrorMessage = null,
            streamErrorCode = null
        )
        val resultSlot = slot<AgentResult>()

        coordinator.finalize(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = emptyList(),
            state = state,
            startTime = 1_000L,
            emit = {}
        )

        verify(exactly = 1) { metrics.recordStreamingExecution(capture(resultSlot)) }
        assertFalse(resultSlot.captured.success) { "Streaming metrics should be recorded as failure" }
        assertEquals("Streaming failed", resultSlot.captured.errorMessage) {
            "Failure metrics should use the default streaming error message"
        }
        assertEquals(AgentErrorCode.UNKNOWN, resultSlot.captured.errorCode) {
            "Missing stream error code should fall back to UNKNOWN"
        }
        assertEquals(1_000L, resultSlot.captured.durationMs) { "Failure duration should be captured from nowMs" }
    }

    @Test
    fun `should close run context even when completion finalizer throws`() = runBlocking {
        val completionFinalizer = mockk<StreamingCompletionFinalizer>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        coEvery {
            completionFinalizer.finalize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("finalizer failed")
        var closeCalled = false
        val coordinator = StreamingFlowLifecycleCoordinator(
            streamingCompletionFinalizer = completionFinalizer,
            agentMetrics = metrics,
            closeRunContext = { closeCalled = true }
        )

        try {
            coordinator.finalize(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
                toolsUsed = emptyList(),
                state = StreamingExecutionState(),
                startTime = 1_000L,
                emit = {}
            )
            fail("Expected IllegalStateException from completion finalizer")
        } catch (e: IllegalStateException) {
            assertEquals("finalizer failed", e.message) {
                "Coordinator should rethrow completion finalizer exceptions"
            }
        }

        assertTrue(closeCalled) { "Run context must be closed even when completion finalizer fails" }
        verify(exactly = 0) { metrics.recordStreamingExecution(any()) }
    }
}
