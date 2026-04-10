package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.MicrometerEvaluationMetricsCollector
import com.arc.reactor.agent.routing.ModelRouter
import com.arc.reactor.agent.routing.ModelSelection
import com.arc.reactor.cache.CacheKeyBuilder
import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.cache.SemanticResponseCache
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.tool.ToolCallback
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AgentExecutionCoordinator에 대한 테스트.
 *
 * 에이전트 실행 조정 로직을 검증합니다.
 */
class AgentExecutionCoordinatorTest {

    @Test
    fun `cache hit일 때 return cached response and skip tool execution해야 한다`() = runTest {
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
    fun `tool execution fails일 때 apply fallback result해야 한다`() = runTest {
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
    fun `finalization 후 cache final successful response해야 한다`() = runTest {
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
                AgentResult.success(
                    content = "This is the final answer with enough content length",
                    toolsUsed = listOf("tool")
                )
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
        assertEquals("This is the final answer with enough content length", result.content)
        verify(exactly = 1) { metrics.recordCacheMiss(expectedKey) }
        coVerify(exactly = 1) {
            responseCache.put(
                expectedKey,
                match {
                    it.content == "This is the final answer with enough content length" &&
                        it.toolsUsed == listOf("tool")
                }
            )
        }
    }

    @Test
    fun `available일 때 prefer semantic cache interface해야 한다`() = runTest {
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
    fun `capture stage timings in hook context metadata해야 한다`() = runTest {
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
    fun `effective maxToolCalls is zero일 때 skip tool selection해야 한다`() = runTest {
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
    fun `include finalizer stage timing in result metadata해야 한다`() = runTest {
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
    fun `register RAG documents as verified sources in hookContext해야 한다`() = runTest {
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

    @Test
    fun `simple math prompt일 때 skip RAG retrieval해야 한다`() = runTest {
        var ragCalled = false
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
            retrieveRagContext = {
                ragCalled = true
                null
            },
            executeWithTools = { _, _, _, _, _, _ -> AgentResult.success(content = "2") },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        coordinator.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "1+1은?"),
            hookContext = HookContext(runId = "run-rag-skip", userId = "u", userPrompt = "1+1은?"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertFalse(ragCalled, "RAG retrieval should be skipped for simple math prompt")
    }

    @Test
    fun `knowledge query prompt일 때 execute RAG retrieval해야 한다`() = runTest {
        var ragCalled = false
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
            retrieveRagContext = {
                ragCalled = true
                null
            },
            executeWithTools = { _, _, _, _, _, _ -> AgentResult.success(content = "answer") },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        coordinator.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "Guard 파이프라인은 몇 단계로 구성되어 있나?"),
            hookContext = HookContext(runId = "run-rag-run", userId = "u", userPrompt = "Guard 파이프라인은 몇 단계로 구성되어 있나?"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(ragCalled, "RAG retrieval should run for knowledge query with '파이프라인' keyword")
    }

    @Test
    fun `low quality response일 때 skip cache storage해야 한다`() = runTest {
        val responseCache = mockk<ResponseCache>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)
        val expectedKey = CacheKeyBuilder.buildKey(command, listOf("tool"))
        coEvery { responseCache.get(expectedKey) } returns null

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
                AgentResult.success(content = "검증 가능한 출처를 찾지 못해 답변을 드리기 어렵습니다.")
            },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-lq", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        coVerify(exactly = 0) { responseCache.put(any(), any()) }
    }

    @Test
    fun `too short response일 때 skip cache storage해야 한다`() = runTest {
        val responseCache = mockk<ResponseCache>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)
        val expectedKey = CacheKeyBuilder.buildKey(command, listOf("tool"))
        coEvery { responseCache.get(expectedKey) } returns null

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
                AgentResult.success(content = "short")
            },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-short", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        coVerify(exactly = 0) { responseCache.put(any(), any()) }
    }

    @Test
    fun `history message count is recorded in hookContext metadata`() = runTest {
        val conversationManager = mockk<com.arc.reactor.memory.ConversationManager>(relaxed = true)
        coEvery { conversationManager.loadHistory(any()) } returns listOf(
            mockk(), mockk(), mockk()
        )

        val hookContext = HookContext(runId = "run-hist", userId = "u", userPrompt = "hi")
        val coordinator = AgentExecutionCoordinator(
            responseCache = null,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = mockk(relaxed = true),
            toolCallbacks = emptyList(),
            mcpToolCallbacks = { emptyList() },
            conversationManager = conversationManager,
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { _, _, _, _, _, _ -> AgentResult.success(content = "answer") },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        coordinator.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertEquals(3, hookContext.metadata[HookMetadataKeys.HISTORY_MESSAGE_COUNT],
            "history_message_count should reflect loaded history size")
    }

    @Test
    fun `cache hit일 때 restore metadata with cacheHit flag해야 한다`() = runTest {
        val responseCache = mockk<ResponseCache>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)
        val expectedKey = CacheKeyBuilder.buildKey(command, listOf("tool"))
        val cachedMetadata = mapOf<String, Any>(
            "grounded" to true,
            "verifiedSourceCount" to 2,
            "answerMode" to "tool_grounded"
        )
        coEvery { responseCache.get(expectedKey) } returns CachedResponse(
            content = "cached-with-meta",
            toolsUsed = listOf("tool"),
            metadata = cachedMetadata
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
            nowMs = { 1_500L }
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-meta", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success, "Cached response should produce a successful result")
        assertEquals("cached-with-meta", result.content, "Content should be restored from cache")
        assertEquals(true, result.metadata["grounded"], "grounded metadata should be restored from cache")
        assertEquals(2, result.metadata["verifiedSourceCount"], "verifiedSourceCount should be restored from cache")
        assertEquals("tool_grounded", result.metadata["answerMode"], "answerMode should be restored from cache")
        assertEquals(true, result.metadata["cacheHit"], "cacheHit flag should be added to restored metadata")
    }

    @Test
    fun `cache store일 때 include result metadata in CachedResponse해야 한다`() = runTest {
        val responseCache = mockk<ResponseCache>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)
        val expectedKey = CacheKeyBuilder.buildKey(command, listOf("tool"))
        coEvery { responseCache.get(expectedKey) } returns null
        coEvery { responseCache.put(expectedKey, any()) } returns Unit

        val resultMetadata = mapOf<String, Any>(
            "grounded" to true,
            "verifiedSourceCount" to 3,
            "stageTimings" to mapOf("agent_loop" to 100L)
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
            executeWithTools = { _, _, _, _, _, _ -> AgentResult.success(content = "raw") },
            finalizeExecution = { _, _, _, _, _ ->
                AgentResult(
                    success = true,
                    content = "This is the final answer with enough content length",
                    toolsUsed = listOf("tool"),
                    metadata = resultMetadata
                )
            },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-store-meta", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        coVerify(exactly = 1) {
            responseCache.put(
                expectedKey,
                match {
                    it.content == "This is the final answer with enough content length" &&
                        it.metadata["grounded"] == true &&
                        it.metadata["verifiedSourceCount"] == 3 &&
                        !it.metadata.containsKey("stageTimings")
                }
            )
        }
    }

    @Test
    fun `modelRouter 설정 시 route 결과로 command model을 교체해야 한다`() = runTest {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val router = object : ModelRouter {
            override fun route(command: AgentCommand): ModelSelection {
                return ModelSelection(
                    modelId = "gemini-2.5-pro",
                    reason = "균형: 높은 복잡도(0.60) → 고급 모델",
                    complexityScore = 0.6
                )
            }
        }
        var capturedModel: String? = null
        val coordinator = AgentExecutionCoordinator(
            responseCache = null,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = metrics,
            modelRouter = router,
            toolCallbacks = emptyList(),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { cmd, _, _, _, _, _ ->
                capturedModel = cmd.model
                AgentResult.success(content = "ok")
            },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        val hookContext = HookContext(
            runId = "run-route", userId = "u", userPrompt = "hi"
        )
        val result = coordinator.execute(
            command = AgentCommand(
                systemPrompt = "sys", userPrompt = "hi"
            ),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success, "라우팅 후 실행이 성공해야 한다")
        assertEquals(
            "gemini-2.5-pro", capturedModel,
            "라우팅된 모델이 executeWithTools에 전달되어야 한다"
        )
        assertEquals(
            "gemini-2.5-pro", result.metadata["modelUsed"],
            "응답 메타에 modelUsed가 포함되어야 한다"
        )
        assertEquals(
            "균형: 높은 복잡도(0.60) → 고급 모델",
            result.metadata["routingReason"],
            "응답 메타에 routingReason이 포함되어야 한다"
        )
        assertEquals(
            0.6, result.metadata["complexityScore"],
            "응답 메타에 complexityScore가 포함되어야 한다"
        )
    }

    @Test
    fun `modelRouter 미설정 시 원본 command model을 유지해야 한다`() = runTest {
        var capturedModel: String? = "SENTINEL"
        val coordinator = AgentExecutionCoordinator(
            responseCache = null,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = mockk(relaxed = true),
            modelRouter = null,
            toolCallbacks = emptyList(),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { cmd, _, _, _, _, _ ->
                capturedModel = cmd.model
                AgentResult.success(content = "ok")
            },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command }
        )

        val result = coordinator.execute(
            command = AgentCommand(
                systemPrompt = "sys", userPrompt = "hi"
            ),
            hookContext = HookContext(
                runId = "run-noroute", userId = "u", userPrompt = "hi"
            ),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success, "라우터 없이도 실행이 성공해야 한다")
        assertNull(
            capturedModel,
            "라우터 미설정 시 command.model은 원본(null)이어야 한다"
        )
        assertNull(
            result.metadata["modelUsed"],
            "라우터 미설정 시 modelUsed 메타가 없어야 한다"
        )
    }

    @Test
    fun `cache hit일 때 finalizeExecution을 통해 output guard를 적용해야 한다`() = runTest {
        val responseCache = mockk<ResponseCache>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)
        val expectedKey = CacheKeyBuilder.buildKey(command, listOf("tool"))
        coEvery { responseCache.get(expectedKey) } returns CachedResponse(
            content = "cached-pii-leak",
            toolsUsed = listOf("tool")
        )

        var finalizeCalled = false
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
            executeWithTools = { _, _, _, _, _, _ ->
                AgentResult.success("live")
            },
            finalizeExecution = { result, _, _, _, _ ->
                finalizeCalled = true
                // Output Guard가 차단한 것을 시뮬레이션
                AgentResult.failure(
                    errorMessage = "Output guard rejected",
                    errorCode = AgentErrorCode.OUTPUT_GUARD_REJECTED
                )
            },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command },
            nowMs = { 1_500L }
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-guard", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(finalizeCalled) {
            "캐시 히트 시에도 finalizeExecution(Output Guard 포함)이 호출되어야 한다"
        }
        assertFalse(result.success) {
            "Output Guard가 거부하면 캐시 히트여도 실패 결과를 반환해야 한다"
        }
        assertEquals(
            AgentErrorCode.OUTPUT_GUARD_REJECTED, result.errorCode,
            "캐시 히트 응답도 Output Guard 거부 코드를 반환해야 한다"
        )
    }

    @Test
    fun `cache hit일 때 cached toolsUsed를 toolsUsed에 반영해야 한다`() = runTest {
        val responseCache = mockk<ResponseCache>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)
        val expectedKey = CacheKeyBuilder.buildKey(command, listOf("mcp", "tool"))
        coEvery { responseCache.get(expectedKey) } returns CachedResponse(
            content = "cached-content with enough length for caching",
            toolsUsed = listOf("search", "calculator")
        )

        var capturedToolsUsed: List<String>? = null
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
            executeWithTools = { _, _, _, _, _, _ -> AgentResult.success("live") },
            finalizeExecution = { result, _, _, tools, _ ->
                capturedToolsUsed = tools
                result
            },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { command, _ -> command },
            nowMs = { 1_500L }
        )

        coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-tools", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertEquals(
            listOf("search", "calculator"), capturedToolsUsed,
            "캐시된 toolsUsed가 finalizeExecution에 전달되어야 한다"
        )
    }

    private fun testTool(name: String): ToolCallback = object : ToolCallback {
        override val name: String = name
        override val description: String = "test-$name"
        override suspend fun call(arguments: Map<String, Any?>): Any = "ok"
    }

    // ========================================================================
    // R253: CACHE stage 자동 기록 테스트
    // ========================================================================

    @Test
    fun `R253 캐시 조회 예외가 CACHE stage로 기록되고 fail-open으로 계속 진행`() = runTest {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)
        val responseCache = mockk<ResponseCache>()
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)

        // get() 호출 시 예외 throw
        coEvery { responseCache.get(any()) } throws IllegalStateException("redis down")

        var executeCalled = false
        val coordinator = AgentExecutionCoordinator(
            responseCache = responseCache,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = mockk(relaxed = true),
            toolCallbacks = listOf(testTool("tool")),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { _, _, _, _, _, _ ->
                executeCalled = true
                AgentResult.success("live response")
            },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { c, _ -> c },
            evaluationMetricsCollector = collector
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        // fail-open — 캐시 조회 실패해도 실행 계속
        assertTrue(result.success) { "fail-open으로 정상 실행 계속" }
        assertEquals("live response", result.content)
        assertTrue(executeCalled) { "캐시 miss로 tool 실행 호출됨" }

        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "cache")
            .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalStateException")
            .counter()
        assertNotNull(counter) {
            "CACHE stage 예외가 기록되어야 한다"
        }
        assertEquals(1.0, counter!!.count())
    }

    @Test
    fun `R253 캐시 저장 예외가 CACHE stage로 기록되고 fail-open으로 반환`() = runTest {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)
        val responseCache = mockk<ResponseCache>()
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)

        // get은 null 반환 (cache miss), put은 예외 throw
        coEvery { responseCache.get(any()) } returns null
        coEvery { responseCache.put(any(), any()) } throws RuntimeException("redis write timeout")

        // MIN_CACHEABLE_CONTENT_LENGTH=30 이상이어야 storeInCache가 put을 호출
        val cacheableContent = "This is a cacheable response content with sufficient length."

        val coordinator = AgentExecutionCoordinator(
            responseCache = responseCache,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = mockk(relaxed = true),
            toolCallbacks = listOf(testTool("tool")),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { _, _, _, _, _, _ -> AgentResult.success(cacheableContent) },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { c, _ -> c },
            evaluationMetricsCollector = collector
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        // fail-open — put 실패해도 성공 결과 반환
        assertTrue(result.success) { "fail-open으로 응답 반환" }
        assertEquals(cacheableContent, result.content)

        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "cache")
            .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "RuntimeException")
            .counter()
        assertNotNull(counter) {
            "put 실패가 CACHE stage로 기록되어야 한다"
        }
        assertEquals(1.0, counter!!.count())
    }

    @Test
    fun `R253 정상 캐시 경로는 execution_error 기록하지 않음`() = runTest {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)
        val responseCache = mockk<ResponseCache>()
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)

        coEvery { responseCache.get(any()) } returns null
        coEvery { responseCache.put(any(), any()) } returns Unit

        val coordinator = AgentExecutionCoordinator(
            responseCache = responseCache,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = mockk(relaxed = true),
            toolCallbacks = listOf(testTool("tool")),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { _, _, _, _, _, _ -> AgentResult.success("live") },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { c, _ -> c },
            evaluationMetricsCollector = collector
        )

        coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .counter()
        assertNull(meter) {
            "정상 캐시 경로(miss + put 성공)에서는 CACHE counter 등록 없음"
        }
    }

    @Test
    fun `R253 기본 NoOp collector backward compat 유지`() = runTest {
        val responseCache = mockk<ResponseCache>()
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", temperature = 0.0)

        coEvery { responseCache.get(any()) } throws IllegalStateException("boom")

        // evaluationMetricsCollector 생략 → NoOp 기본값
        val coordinator = AgentExecutionCoordinator(
            responseCache = responseCache,
            cacheableTemperature = 0.0,
            defaultTemperature = 0.3,
            fallbackStrategy = null,
            agentMetrics = mockk(relaxed = true),
            toolCallbacks = listOf(testTool("tool")),
            mcpToolCallbacks = { emptyList() },
            conversationManager = mockk(relaxed = true),
            selectAndPrepareTools = { emptyList() },
            retrieveRagContext = { null },
            executeWithTools = { _, _, _, _, _, _ -> AgentResult.success("live") },
            finalizeExecution = { result, _, _, _, _ -> result },
            checkGuardAndHooks = { _, _, _ -> null },
            resolveIntent = { c, _ -> c }
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        // fail-open 동작은 그대로
        assertTrue(result.success) { "NoOp collector에서도 fail-open으로 정상 반환" }
    }
}
