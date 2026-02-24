package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class StreamingCompletionFinalizerTest {

    @Test
    fun `should save history and call after hook on streaming success`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = StreamingCompletionFinalizer(
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            agentMetrics = metrics
        )

        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi")

        finalizer.finalize(
            command = command,
            hookContext = hookContext,
            streamStarted = true,
            streamSuccess = true,
            collectedContent = "stream done",
            lastIterationContent = "final chunk",
            streamErrorMessage = null,
            toolsUsed = listOf("search"),
            emit = {}
        )

        coVerify(exactly = 1) { conversationManager.saveStreamingHistory(command, "final chunk") }
        coVerify(exactly = 1) {
            hookExecutor.executeAfterAgentComplete(
                context = hookContext,
                response = match {
                    it.success &&
                        it.response == "stream done" &&
                        it.errorMessage == null &&
                        it.toolsUsed == listOf("search")
                }
            )
        }
    }

    @Test
    fun `should emit max boundary marker and record violation when output exceeds max`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = StreamingCompletionFinalizer(
            boundaries = BoundaryProperties(outputMaxChars = 3),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            agentMetrics = metrics
        )

        val emitted = mutableListOf<String>()
        finalizer.finalize(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            streamStarted = false,
            streamSuccess = true,
            collectedContent = "abcd",
            lastIterationContent = "abcd",
            streamErrorMessage = null,
            toolsUsed = emptyList(),
            emit = { emitted.add(it) }
        )

        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_long", "warn", 3, 4) }
        assertEquals(1, emitted.size)
        val parsed = StreamEventMarker.parse(emitted.first())
        assertEquals("error", parsed?.first)
        assertTrue(parsed?.second?.contains("Boundary violation [output_too_long]") == true)
    }

    @Test
    fun `should treat RETRY_ONCE min boundary policy as warn in streaming`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = StreamingCompletionFinalizer(
            boundaries = BoundaryProperties(outputMinChars = 5, outputMinViolationMode = OutputMinViolationMode.RETRY_ONCE),
            conversationManager = mockk(relaxed = true),
            hookExecutor = null,
            agentMetrics = metrics
        )

        val emitted = mutableListOf<String>()
        finalizer.finalize(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            streamStarted = true,
            streamSuccess = true,
            collectedContent = "abc",
            lastIterationContent = "abc",
            streamErrorMessage = null,
            toolsUsed = emptyList(),
            emit = { emitted.add(it) }
        )

        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_short", "warn", 5, 3) }
        assertEquals(1, emitted.size)
        val parsed = StreamEventMarker.parse(emitted.first())
        assertEquals("error", parsed?.first)
        assertTrue(parsed?.second?.contains("Boundary violation [output_too_short]") == true)
    }

    @Test
    fun `should rethrow cancellation from streaming after hook`() = runBlocking {
        val hookExecutor = mockk<HookExecutor>()
        coEvery { hookExecutor.executeAfterAgentComplete(any(), any()) } throws CancellationException("cancelled")
        val finalizer = StreamingCompletionFinalizer(
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = hookExecutor,
            agentMetrics = mockk(relaxed = true)
        )

        try {
            finalizer.finalize(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
                streamStarted = true,
                streamSuccess = false,
                collectedContent = "",
                lastIterationContent = "",
                streamErrorMessage = "error",
                toolsUsed = emptyList(),
                emit = {}
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `should rethrow cancellation when emitting boundary marker fails`() = runBlocking {
        val finalizer = StreamingCompletionFinalizer(
            boundaries = BoundaryProperties(outputMaxChars = 1),
            conversationManager = mockk(relaxed = true),
            hookExecutor = null,
            agentMetrics = mockk(relaxed = true)
        )

        try {
            finalizer.finalize(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
                streamStarted = false,
                streamSuccess = true,
                collectedContent = "too-long",
                lastIterationContent = "too-long",
                streamErrorMessage = null,
                toolsUsed = emptyList(),
                emit = { throw CancellationException("cancelled") }
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
