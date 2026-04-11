package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.MicrometerEvaluationMetricsCollector
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ToolPreparationPlanner에 대한 테스트.
 *
 * 도구 준비 계획 수립 로직을 검증합니다.
 */
class ToolPreparationPlannerTest {

    @Test
    fun `combines은(는) local and callback tools with max limit`() {
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
    fun `selector to merged callbacks를 적용한다`() {
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
    fun `call selector when callbacks are empty하지 않는다`() {
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

    @Test
    fun `callback names across local callback lists and mcp callbacks를 중복 제거한다`() {
        val callbackFromLocalList = AgentTestFixture.toolCallback("duplicate-tool")
        val callbackFromMcp = AgentTestFixture.toolCallback("duplicate-tool")
        val uniqueCallback = AgentTestFixture.toolCallback("unique-tool")
        val planner = ToolPreparationPlanner(
            localTools = emptyList(),
            toolCallbacks = listOf(callbackFromLocalList, uniqueCallback),
            mcpToolCallbacks = { listOf(callbackFromMcp) },
            toolSelector = null,
            maxToolsPerRequest = 10,
            fallbackToolTimeoutMs = 150
        )

        val prepared = planner.prepareForPrompt("run duplicate check")

        assertEquals(2, prepared.size, "Expected duplicate callback names to be removed before wrapping")
        assertTrue(
            prepared.all { it is ArcToolCallbackAdapter },
            "Expected only wrapped callback tools in prepared list"
        )
        val wrappedCallbacks = prepared.map { (it as ArcToolCallbackAdapter).arcCallback }
        assertEquals(
            listOf("duplicate-tool", "unique-tool"),
            wrappedCallbacks.map(ToolCallback::name),
            "Expected first duplicate callback to be kept in stable order"
        )
        assertTrue(
            wrappedCallbacks.first() === callbackFromLocalList,
            "Expected duplicate resolution to keep the first callback instance"
        )
    }

    @Test
    fun `reuses은(는) callback adapters for stable callback instances`() {
        val callback = AgentTestFixture.toolCallback("stable-tool")
        val planner = ToolPreparationPlanner(
            localTools = emptyList(),
            toolCallbacks = listOf(callback),
            mcpToolCallbacks = { emptyList() },
            toolSelector = null,
            maxToolsPerRequest = 10,
            fallbackToolTimeoutMs = 150
        )

        val firstPrepared = planner.prepareForPrompt("first")
        val secondPrepared = planner.prepareForPrompt("second")

        assertEquals(1, firstPrepared.size)
        assertEquals(1, secondPrepared.size)
        assertTrue(
            firstPrepared.first() === secondPrepared.first(),
            "Planner should reuse the same adapter instance for stable callback objects"
        )
    }

    @Test
    fun `reuse adapters for distinct callback instances with the same name하지 않는다`() {
        val planner = ToolPreparationPlanner(
            localTools = emptyList(),
            toolCallbacks = emptyList(),
            mcpToolCallbacks = {
                listOf(AgentTestFixture.toolCallback("dynamic-tool"))
            },
            toolSelector = null,
            maxToolsPerRequest = 10,
            fallbackToolTimeoutMs = 150
        )

        val firstPrepared = planner.prepareForPrompt("first")
        val secondPrepared = planner.prepareForPrompt("second")

        assertEquals(1, firstPrepared.size)
        assertEquals(1, secondPrepared.size)
        assertTrue(
            firstPrepared.first() !== secondPrepared.first(),
            "Planner should not pin a stale adapter when callback instances are recreated"
        )
    }

    // ========================================================================
    // R247: evaluationMetricsCollector 자동 배선 테스트
    // ========================================================================

    @Test
    fun `R247 주입된 collector가 adapter에 전달되어 tool call 예외를 기록해야 한다`() {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)

        val failingCallback = object : ToolCallback {
            override val name: String = "failing_planner_tool"
            override val description: String = "fails"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                throw IllegalStateException("planner injected failure")
            }
        }
        val planner = ToolPreparationPlanner(
            localTools = emptyList(),
            toolCallbacks = listOf(failingCallback),
            mcpToolCallbacks = { emptyList() },
            toolSelector = null,
            maxToolsPerRequest = 10,
            fallbackToolTimeoutMs = 150,
            evaluationMetricsCollector = collector
        )

        val prepared = planner.prepareForPrompt("do something")
        val adapter = prepared.first() as ArcToolCallbackAdapter
        adapter.call("{}")

        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "tool_call")
            .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalStateException")
            .counter()
        assertNotNull(counter) {
            "planner에 주입된 collector가 adapter에 전파되어 예외가 기록되어야 한다"
        }
        assertEquals(1.0, counter!!.count())
    }

    @Test
    fun `R247 collector 미주입 시 기본 NoOp으로 backward compat 유지`() {
        val failingCallback = object : ToolCallback {
            override val name: String = "failing_tool"
            override val description: String = "fails"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                throw RuntimeException("boom")
            }
        }
        // evaluationMetricsCollector 파라미터 생략 → 기본 NoOp
        val planner = ToolPreparationPlanner(
            localTools = emptyList(),
            toolCallbacks = listOf(failingCallback),
            mcpToolCallbacks = { emptyList() },
            toolSelector = null,
            maxToolsPerRequest = 10,
            fallbackToolTimeoutMs = 150
        )

        val prepared = planner.prepareForPrompt("prompt")
        val adapter = prepared.first() as ArcToolCallbackAdapter
        val output = adapter.call("{}")

        assertTrue(output.startsWith("Error:")) {
            "기본 NoOp collector에서도 예외 처리 경로는 그대로 작동"
        }
        // R271: exception 클래스명 LLM 노출 제거 — tool name만 포함됨
        assertTrue(output.contains("failing_tool")) {
            "에러 메시지에 tool name 포함"
        }
        assertFalse(
            output.contains("RuntimeException"),
            "R271 fix: exception 클래스명이 LLM 출력에 노출되면 안 됨"
        )
    }
}
