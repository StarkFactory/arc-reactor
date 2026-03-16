package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.tool.LocalTool
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

@Tag("matrix")
/**
 * ToolPreparationPlanner의 매트릭스 테스트.
 *
 * 다양한 도구 구성에 대한 계획 수립을 검증합니다.
 */
class ToolPreparationPlannerMatrixTest {

    @Test
    fun `planner은(는) satisfy size and ordering invariants across 750 combinations해야 한다`() {
        var checked = 0
        for (localCount in 0..4) {
            for (callbackCount in 0..4) {
                for (mcpCount in 0..4) {
                    for (maxTools in 1..6) {
                        val localTools = (0 until localCount).map { object : LocalTool {} }
                        val callbacks = (0 until callbackCount).map { AgentTestFixture.toolCallback("cb-$it") }
                        val mcpCallbacks = (0 until mcpCount).map { AgentTestFixture.toolCallback("mcp-$it") }

                        val planner = ToolPreparationPlanner(
                            localTools = localTools,
                            toolCallbacks = callbacks,
                            mcpToolCallbacks = { mcpCallbacks },
                            toolSelector = null,
                            maxToolsPerRequest = maxTools,
                            fallbackToolTimeoutMs = 200
                        )

                        val prepared = planner.prepareForPrompt("prompt-$checked")
                        val expectedTotal = minOf(maxTools, localCount + callbackCount + mcpCount)
                        assertEquals(expectedTotal, prepared.size, "combination=$checked")

                        val expectedLocalPrefix = minOf(localCount, maxTools)
                        repeat(expectedLocalPrefix) { i ->
                            assertInstanceOf(
                                LocalTool::class.java,
                                prepared[i],
                                "combination=$checked index=$i should be LocalTool"
                            )
                        }

                        val expectedCallbacks = expectedTotal - expectedLocalPrefix
                        assertEquals(
                            expectedCallbacks,
                            prepared.count { it is ArcToolCallbackAdapter },
                            "combination=$checked"
                        )

                        checked++
                    }
                }
            }
        }
        assertEquals(750, checked)
    }

    @Test
    fun `selector output은(는) be wrapped and truncated predictably해야 한다`() {
        val selected = (0 until 6).map { AgentTestFixture.toolCallback("selected-$it") }
        val planner = ToolPreparationPlanner(
            localTools = listOf(object : LocalTool {}, object : LocalTool {}),
            toolCallbacks = listOf(AgentTestFixture.toolCallback("unused")),
            mcpToolCallbacks = { listOf(AgentTestFixture.toolCallback("unused-mcp")) },
            toolSelector = object : com.arc.reactor.tool.ToolSelector {
                override fun select(prompt: String, availableTools: List<com.arc.reactor.tool.ToolCallback>) = selected
            },
            maxToolsPerRequest = 5,
            fallbackToolTimeoutMs = 200
        )

        val prepared = planner.prepareForPrompt("critical query")

        assertEquals(5, prepared.size)
        assertInstanceOf(LocalTool::class.java, prepared[0]) {
            "First prepared tool should be local tool"
        }
        assertInstanceOf(LocalTool::class.java, prepared[1]) {
            "Second prepared tool should be local tool"
        }
        assertEquals(3, prepared.count { it is ArcToolCallbackAdapter })
    }
}
