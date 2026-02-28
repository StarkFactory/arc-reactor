package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolPreparationPlannerTest {

    @Test
    fun `combines local and callback tools with max limit`() {
        val localA = object : LocalTool {}
        val localB = object : LocalTool {}
        val callbackA = AgentTestFixture.toolCallback("callback-a")
        val callbackB = AgentTestFixture.toolCallback("callback-b")
        val callbackC = AgentTestFixture.toolCallback("callback-c")
        val planner = ToolPreparationPlanner(
            localTools = listOf(localA, localB),
            toolCallbacks = listOf(callbackA, callbackB),
            mcpToolCallbacks = { listOf(callbackC) },
            toolSelector = null,
            maxToolsPerRequest = 3,
            fallbackToolTimeoutMs = 150
        )

        val prepared = planner.prepareForPrompt("hello")

        assertEquals(3, prepared.size)
        assertTrue(prepared[0] === localA, "First prepared tool should be localA (identity check)")
        assertTrue(prepared[1] === localB, "Second prepared tool should be localB (identity check)")
        assertInstanceOf(ArcToolCallbackAdapter::class.java, prepared[2]) {
            "Third prepared tool should wrap callback as ArcToolCallbackAdapter"
        }
    }

    @Test
    fun `applies selector to merged callbacks`() {
        val selector = mockk<ToolSelector>()
        val callbackA = AgentTestFixture.toolCallback("callback-a")
        val callbackB = AgentTestFixture.toolCallback("callback-b")
        every { selector.select("find data", any()) } returns listOf(callbackB)
        val planner = ToolPreparationPlanner(
            localTools = emptyList(),
            toolCallbacks = listOf(callbackA),
            mcpToolCallbacks = { listOf(callbackB) },
            toolSelector = selector,
            maxToolsPerRequest = 10,
            fallbackToolTimeoutMs = 150
        )

        val prepared = planner.prepareForPrompt("find data")

        verify(exactly = 1) {
            selector.select(
                "find data",
                match { it.map(ToolCallback::name).toSet() == setOf("callback-a", "callback-b") }
            )
        }
        assertEquals(1, prepared.size)
        val adapter = prepared.first() as ArcToolCallbackAdapter
        assertEquals("callback-b", adapter.arcCallback.name)
    }

    @Test
    fun `does not call selector when callbacks are empty`() {
        val selector = mockk<ToolSelector>()
        val local = object : LocalTool {}
        val planner = ToolPreparationPlanner(
            localTools = listOf(local),
            toolCallbacks = emptyList(),
            mcpToolCallbacks = { emptyList() },
            toolSelector = selector,
            maxToolsPerRequest = 10,
            fallbackToolTimeoutMs = 150
        )

        val prepared = planner.prepareForPrompt("anything")

        verify(exactly = 0) { selector.select(any(), any()) }
        assertEquals(1, prepared.size)
        assertTrue(prepared.first() === local, "Local tool should be returned as-is without wrapping")
    }
}
