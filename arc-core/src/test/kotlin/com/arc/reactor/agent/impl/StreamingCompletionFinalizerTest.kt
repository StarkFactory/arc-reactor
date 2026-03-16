package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
            streamErrorCode = null,
            toolsUsed = listOf("search"),
            startTime = 1_000L,
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
            streamErrorCode = null,
            toolsUsed = emptyList(),
            startTime = 1_000L,
            emit = { emitted.add(it) }
        )

        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_long", "warn", 3, 4) }
        val errorMarkers = emitted.filter { StreamEventMarker.parse(it)?.first == "error" }
        assertEquals(1, errorMarkers.size, "Exactly one error marker should be emitted")
        val parsed = StreamEventMarker.parse(errorMarkers.first())
        assertEquals("error", parsed?.first, "Event type should be error")
        assertTrue(parsed?.second?.contains("Boundary violation [output_too_long]") == true, "Error marker should describe output_too_long boundary violation")
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
            streamErrorCode = null,
            toolsUsed = emptyList(),
            startTime = 1_000L,
            emit = { emitted.add(it) }
        )

        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_short", "warn", 5, 3) }
        val errorMarkers = emitted.filter { StreamEventMarker.parse(it)?.first == "error" }
        assertEquals(1, errorMarkers.size, "Exactly one error marker should be emitted")
        val parsed = StreamEventMarker.parse(errorMarkers.first())
        assertEquals("error", parsed?.first, "Event type should be error")
        assertTrue(parsed?.second?.contains("Boundary violation [output_too_short]") == true, "Error marker should describe output_too_short boundary violation")
    }

    @Test
    fun `should not save history when output guard pipeline throws (fail-close)`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val outputGuardPipeline = mockk<OutputGuardPipeline>()
        coEvery { outputGuardPipeline.check(any(), any()) } throws RuntimeException("moderation API down")
        val finalizer = StreamingCompletionFinalizer(
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = null,
            agentMetrics = mockk(relaxed = true),
            outputGuardPipeline = outputGuardPipeline
        )

        val emitted = mutableListOf<String>()
        finalizer.finalize(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            streamStarted = true,
            streamSuccess = true,
            collectedContent = "unsafe content",
            lastIterationContent = "unsafe content",
            streamErrorMessage = null,
            streamErrorCode = null,
            toolsUsed = emptyList(),
            startTime = 1_000L,
            emit = { emitted.add(it) }
        )

        coVerify(exactly = 0) { conversationManager.saveStreamingHistory(any(), any()) }
        assertTrue(emitted.any { it.contains("Output guard check failed") },
            "Should emit error marker when output guard crashes")
    }

    @Test
    fun `should not save history when streaming LLM returns empty content`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val finalizer = StreamingCompletionFinalizer(
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            agentMetrics = mockk(relaxed = true)
        )

        finalizer.finalize(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            streamStarted = true,
            streamSuccess = true,
            collectedContent = "",
            lastIterationContent = "",
            streamErrorMessage = null,
            streamErrorCode = null,
            toolsUsed = emptyList(),
            startTime = 1_000L,
            emit = {}
        )

        coVerify(exactly = 0) { conversationManager.saveStreamingHistory(any(), any()) }
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
                streamErrorCode = "UNKNOWN",
                toolsUsed = emptyList(),
                startTime = 1_000L,
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
                streamErrorCode = null,
                toolsUsed = emptyList(),
                startTime = 1_000L,
                emit = { throw CancellationException("cancelled") }
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `should emit done marker with RAG metadata when documents were retrieved`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = StreamingCompletionFinalizer(
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = null,
            agentMetrics = metrics,
            nowMs = { 2_000L }
        )

        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi")
        hookContext.metadata[RagContextRetriever.METADATA_RAG_DOCUMENT_COUNT] = 3
        hookContext.metadata[RagContextRetriever.METADATA_RAG_SOURCES] = listOf("faq.md", "guide.md")
        recordStageTiming(hookContext, "rag_retrieval", 42L)

        val emitted = mutableListOf<String>()
        finalizer.finalize(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = hookContext,
            streamStarted = true,
            streamSuccess = true,
            collectedContent = "answer",
            lastIterationContent = "answer",
            streamErrorMessage = null,
            streamErrorCode = null,
            toolsUsed = listOf("search"),
            startTime = 1_000L,
            emit = { emitted.add(it) }
        )

        val doneMarkers = emitted.filter { StreamEventMarker.parse(it)?.first == "done" }
        assertEquals(1, doneMarkers.size) { "Exactly one done marker should be emitted" }

        val donePayload = StreamEventMarker.parse(doneMarkers.first())!!.second
        val mapper = jacksonObjectMapper()
        val metadata = mapper.readValue(donePayload, Map::class.java)

        assertEquals(3, metadata["ragDocumentCount"]) { "Done metadata should contain ragDocumentCount" }
        @Suppress("UNCHECKED_CAST")
        val sources = metadata["ragSources"] as List<String>
        assertTrue(sources.contains("faq.md")) { "Done metadata should contain ragSources" }
        assertEquals(listOf("search"), metadata["toolsUsed"]) { "Done metadata should contain toolsUsed" }
        assertEquals(1000, metadata["totalDurationMs"]) { "Done metadata should contain totalDurationMs" }

        @Suppress("UNCHECKED_CAST")
        val stageTimings = metadata["stageTimings"] as Map<String, Any>
        assertEquals(42, stageTimings["rag_retrieval"]) { "Done metadata stageTimings should include rag_retrieval" }
    }

    @Test
    fun `should emit done marker without RAG metadata when no documents retrieved`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = StreamingCompletionFinalizer(
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = null,
            agentMetrics = metrics,
            nowMs = { 2_000L }
        )

        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi")

        val emitted = mutableListOf<String>()
        finalizer.finalize(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = hookContext,
            streamStarted = true,
            streamSuccess = true,
            collectedContent = "answer",
            lastIterationContent = "answer",
            streamErrorMessage = null,
            streamErrorCode = null,
            toolsUsed = emptyList(),
            startTime = 1_000L,
            emit = { emitted.add(it) }
        )

        val doneMarkers = emitted.filter { StreamEventMarker.parse(it)?.first == "done" }
        assertEquals(1, doneMarkers.size) { "Exactly one done marker should be emitted" }

        val donePayload = StreamEventMarker.parse(doneMarkers.first())!!.second
        val mapper = jacksonObjectMapper()
        val metadata = mapper.readValue(donePayload, Map::class.java)

        assertTrue(!metadata.containsKey("ragDocumentCount")) {
            "Done metadata should not contain ragDocumentCount when no RAG documents"
        }
        assertTrue(!metadata.containsKey("ragSources")) {
            "Done metadata should not contain ragSources when no RAG documents"
        }
    }
}
