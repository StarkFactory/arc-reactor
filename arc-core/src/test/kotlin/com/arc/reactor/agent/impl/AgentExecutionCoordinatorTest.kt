package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.cache.CacheKeyBuilder
import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.hook.model.HookContext
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
            resolveIntent = { it },
            nowMs = { 1_500L }
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success)
        assertEquals("cached", result.content)
        assertEquals(500L, result.durationMs)
        assertFalse(executeCalled)
        verify(exactly = 1) { metrics.recordCacheHit(expectedKey) }
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
            resolveIntent = { it }
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success)
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
            resolveIntent = { it }
        )

        val result = coordinator.execute(
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            startTime = 1_000L
        )

        assertTrue(result.success)
        assertEquals("final", result.content)
        verify(exactly = 1) { metrics.recordCacheMiss(expectedKey) }
        coVerify(exactly = 1) { responseCache.put(expectedKey, match { it.content == "final" && it.toolsUsed == listOf("tool") }) }
    }

    private fun testTool(name: String): ToolCallback = object : ToolCallback {
        override val name: String = name
        override val description: String = "test-$name"
        override suspend fun call(arguments: Map<String, Any?>): Any = "ok"
    }
}
