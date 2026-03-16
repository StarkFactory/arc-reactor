package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.cache.CacheKeyBuilder
import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.cache.SemanticResponseCache
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentExecutionCoordinatorTest {

    @Test
    fun `should return cached response and skip tool execution when cache hit`() = runBlocking {
        val responseCache = mockk<ResponseCache>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)
        val expectedKey = CacheKeyBuilder.buildKey(command, listOf("mcp", "tool"))
        coEvery { responseCache.get(expectedKey) } returns CachedResponse(
            content = "cached",
            toolsUsed = listOf("tool")
        )

        var executeCalled = false
        val coordinator = AgentExecutionCoordinator(
            responseCache = responseCache,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = metrics,
            toolCallbacks = listOf(testTool("tool")),
            mcpToolCallbacks = { listOf(testTool("mcp")) },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { _, _, _, _, _, _ ->
                executeCalled = true
                AgentResult.success("live")
            },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command },
            nowMs = { 1_500L }
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success, "Cached response should produce a successful result")
        assertEquals("cached", result.content)
        assertEquals(500L, result.durationMs)
        assertFalse(executeCalled, "Agent executor should not be called when cache hit")
        verify(exactly = 1) { metrics.recordExactCacheHit(expectedKey) }
        verify(exactly = 0) { metrics.recordCacheMiss(expectedKey) }
    }

    @Test
    fun `should apply fallback result when tool execution fails`() = runBlocking {
        val fallback = mockk<FallbackStrategy>()
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
        coEvery {
            fallback.execute(command, match { it.message == "boom" })
        } returns AgentResult.success(content = "fallback")

        var finalizedInput: AgentResult? = null
        val coordinator = AgentExecutionCoordinator(
            responseCache = null,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = fallback,
            agentMetrics = mockk(relaxed = true),
            toolCallbacks = emptyList(),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { _, _, _, _, _, _ ->
                AgentResult.failure(errorMessage = "boom", errorCode = AgentErrorCode.UNKNOWN)
            },
            finalizeExecution = { result, _, _, _, _ ->
                finalizedInput = result
                result
            },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success, "Fallback strategy should produce a successful result")
        assertEquals("fallback", result.content)
        assertEquals("fallback", finalizedInput?.content)
        coVerify(exactly = 1) { fallback.execute(command, match { it.message == "boom" }) }
    }

    @Test
    fun `should cache final successful response after finalization`() = runBlocking {
        val responseCache = mockk<ResponseCache>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)
        val expectedKey = CacheKeyBuilder.buildKey(command, listOf("tool"))
        coEvery { responseCache.get(expectedKey) } returns null
        coEvery { responseCache.put(expectedKey, match { it.content == "final" }) } returns Unit

        val coordinator = AgentExecutionCoordinator(
            responseCache = responseCache,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = metrics,
            toolCallbacks = listOf(testTool("tool")),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { _, _, _, _, _, _ -> AgentResult.success(content = "raw") },
            finalizeExecution = { _, _, _, _, _ ->
                AgentResult.success(content = "final", toolsUsed = listOf("tool"))
            },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success, "Finalized response should produce a successful result")
        assertEquals("final", result.content)
        verify(exactly = 1) { metrics.recordCacheMiss(expectedKey) }
        coVerify(exactly = 1) {
            responseCache.put(
                expectedKey,
                match { it.content == "final" && it.toolsUsed == listOf("tool") }
            )
        }
    }

    @Test
    fun `should prefer semantic cache interface when available`() = runBlocking {
        val responseCache = mockk<SemanticResponseCache>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)
        val expectedKey = CacheKeyBuilder.buildKey(command, listOf("tool"))
        coEvery { responseCache.get(expectedKey) } returns null
        coEvery { responseCache.getSemantic(command, listOf("tool"), expectedKey) } returns CachedResponse(
            content = "semantic-hit",
            toolsUsed = listOf("tool")
        )

        val coordinator = AgentExecutionCoordinator(
            responseCache = responseCache,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = metrics,
            toolCallbacks = listOf(testTool("tool")),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { _, _, _, _, _, _ -> AgentResult.success("live") },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command },
            nowMs = { 2_000L }
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success, "Semantic cache hit should return success")
        assertEquals("semantic-hit", result.content)
        coVerify(exactly = 1) { responseCache.get(expectedKey) }
        coVerify(exactly = 1) { responseCache.getSemantic(command, listOf("tool"), expectedKey) }
        verify(exactly = 1) { metrics.recordSemanticCacheHit(expectedKey) }
    }

    @Test
    fun `should capture stage timings in hook context metadata`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi")
        val coordinator = AgentExecutionCoordinator(
            responseCache = null,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = metrics,
            toolCallbacks = listOf(testTool("tool")),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { _, _, _, context, _, _ ->
                recordStageTiming(context, "llm_calls", 11)
                recordStageTiming(context, "tool_execution", 7)
                AgentResult.success(content = "raw")
            },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, context ->
                recordStageTiming(context, "intent_resolution", 3)
                command
            }
        )

        val result = coordinator.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success, "Coordinator should still return the successful result")
        val stageTimings = readStageTimings(hookContext)
        assertTrue(stageTimings.containsKey("cache_lookup"), "cache_lookup timing should be recorded")
        assertTrue(stageTimings.containsKey("history_load"), "history_load timing should be recorded")
        assertTrue(stageTimings.containsKey("rag_retrieval"), "rag_retrieval timing should be recorded")
        assertTrue(stageTimings.containsKey("tool_selection"), "tool_selection timing should be recorded")
        assertTrue(stageTimings.containsKey("agent_loop"), "agent_loop timing should be recorded")
        assertTrue(stageTimings.containsKey("finalizer"), "finalizer timing should be recorded")
        assertEquals(11L, stageTimings["llm_calls"], "llm_calls timing should be preserved")
        assertEquals(7L, stageTimings["tool_execution"], "tool_execution timing should be preserved")
        verify(atLeast = 1) { metrics.recordStageLatency(any(), any(), any()) }
        verify(exactly = 1) { metrics.recordStageLatency("llm_calls", 11, any()) }
        verify(exactly = 1) { metrics.recordStageLatency("tool_execution", 7, any()) }
    }

    @Test
    fun `should skip tool selection when effective maxToolCalls is zero`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        var selectCalled = false
        var capturedTools: List<Any>? = null
        val coordinator = AgentExecutionCoordinator(
            responseCache = null,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            maxToolCallsLimit = 0,
            fallbackStrategy = null,
            agentMetrics = metrics,
            toolCallbacks = listOf(testTool("tool")),
            mcpToolCallbacks = { listOf(testTool("mcp")) },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = {
                selectCalled = true
                listOf("selected-tool")
            },
            retrieveRagContext = { null },
            executeWithTools = { _, tools, _, _, _, _ ->
                capturedTools = tools
                AgentResult.success(content = "raw")
            },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command.copy(maxToolCalls = 5) }
        )

        val hookContext = HookContext(runId = "run-2", userId = "u", userPrompt = "hi")
        val result = coordinator.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", maxToolCalls = 10),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success, "Coordinator should succeed when tools are disabled by zero budget")
        assertFalse(selectCalled, "Tool selection should be skipped when effective maxToolCalls is zero")
        assertEquals(emptyList<Any>(), capturedTools, "No tools should be prepared when the tool budget is zero")
        assertTrue(readStageTimings(hookContext).containsKey("tool_selection")) {
            "tool_selection timing should still be recorded even when skipped"
        }
    }

    @Test
    fun `should include finalizer stage timing in result metadata`() = runBlocking {
        val coordinator = AgentExecutionCoordinator(
            responseCache = null,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = mockk(relaxed = true),
            toolCallbacks = emptyList(),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { _, _, _, _, _, _ -> AgentResult.success(content = "raw") },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        val result = coordinator.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        @Suppress("UNCHECKED_CAST")
        val stageTimings = result.metadata["stageTimings"] as? Map<String, Any>
        assertTrue(stageTimings?.containsKey("finalizer") == true) {
            "Final result metadata should include the finalizer stage timing"
        }
    }

    @Test
    fun `should register RAG documents as verified sources in hookContext`() = runBlocking {
        val hookContext = HookContext(runId = "run-rag", userId = "u", userPrompt = "refund policy")
        val ragContext = RagContext(
            context = "Refund policy context",
            documents = listOf(
                RetrievedDocument(
                    id = "doc-1",
                    content = "Refund within 30 days",
                    metadata = mapOf("title" to "Refund Policy"),
                    source = "https://docs.example.com/refund"
                ),
                RetrievedDocument(
                    id = "doc-2",
                    content = "No source document",
                    metadata = mapOf("title" to "No Source")
                ),
                RetrievedDocument(
                    id = "doc-3",
                    content = "Another doc",
                    source = "https://docs.example.com/returns"
                )
            )
        )

        val coordinator = AgentExecutionCoordinator(
            responseCache = null,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = mockk(relaxed = true),
            toolCallbacks = emptyList(),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { ragContext },
            executeWithTools = { _, _, _, _, _, ragCtx ->
                assertEquals("Refund policy context", ragCtx, "RAG context string should be passed to executeWithTools")
                AgentResult.success(content = "answer")
            },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        coordinator.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "refund policy"),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        val sources = hookContext.verifiedSources
        assertEquals(2, sources.size, "Only documents with non-blank source should become verified sources")
        assertEquals("Refund Policy", sources[0].title, "Title should come from document metadata")
        assertEquals("https://docs.example.com/refund", sources[0].url, "URL should come from document source")
        assertEquals("rag", sources[0].toolName, "Tool name should be 'rag'")
        assertEquals("doc-3", sources[1].title, "Title should fall back to document id when metadata has no title")
        assertEquals("https://docs.example.com/returns", sources[1].url, "URL should come from document source")
    }

    private fun testTool(name: String): ToolCallback = object : ToolCallback {
        override val name: String = name
        override val description: String = "test-$name"
        override suspend fun call(arguments: Map<String, Any?>): Any = "ok"
    }
}
