package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.ExecutionStage
import com.arc.reactor.agent.metrics.MicrometerEvaluationMetricsCollector
import com.arc.reactor.tool.ToolCallback
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

/**
 * ArcToolCallbackAdapter에 대한 테스트.
 *
 * ToolCallback을 Spring AI ToolCallback으로 변환하는
 * 어댑터의 동작을 검증합니다.
 */
class ArcToolCallbackAdapterTest {

    @Test
    fun `tool execution succeeds일 때 callback output를 반환한다`() {
        val callback = object : ToolCallback {
            override val name: String = "echo"
            override val description: String = "echo tool"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                return "echo:${arguments["message"]}"
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 500)

        val output = adapter.call("""{"message":"arc"}""")

        assertEquals("echo:arc", output, "Should return tool output as-is")
    }

    @Test
    fun `callback exceeds configured timeout일 때 error string를 반환한다`() {
        val callback = object : ToolCallback {
            override val name: String = "slow_tool"
            override val description: String = "slow tool"
            override val timeoutMs: Long = 30
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                delay(200)
                return "late"
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 500)

        lateinit var output: String
        val elapsedMs = measureTimeMillis {
            output = adapter.call("{}")
        }

        assertTrue(output.startsWith("Error:"), "Timeout should return error string, got: $output")
        assertTrue(output.contains("timed out after 30ms")) {
            "Timeout error should mention the timeout duration, got: $output"
        }
        assertTrue(output.contains("slow_tool"), "Timeout error should mention the tool name, got: $output")
        assertTrue(elapsedMs < 200, "Expected timeout to abort before full tool delay, elapsed=${elapsedMs}ms")
    }

    @Test
    fun `callback blocks thread with delay일 때 error string를 반환한다`() {
        val callback = object : ToolCallback {
            override val name: String = "blocking_tool"
            override val description: String = "blocking tool"
            override val timeoutMs: Long = 40
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                delay(300)
                return "late"
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 500)

        lateinit var output: String
        val elapsedMs = measureTimeMillis {
            output = adapter.call("{}")
        }

        assertTrue(output.startsWith("Error:"), "Timeout should return error string, got: $output")
        assertTrue(output.contains("timed out after 40ms")) {
            "Timeout should mention configured timeout, got: $output"
        }
        assertTrue(output.contains("blocking_tool"), "Timeout should mention tool name, got: $output")
        assertTrue(elapsedMs < 200) {
            "Callback should be cancelled by timeout, elapsed=${elapsedMs}ms"
        }
    }

    @Test
    fun `callback throws non-cancellation exception일 때 error string를 반환한다`() {
        val callback = object : ToolCallback {
            override val name: String = "failing_tool"
            override val description: String = "tool that throws"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                throw IllegalStateException("disk full")
            }
        }
        val adapter = ArcToolCallbackAdapter(callback, fallbackToolTimeoutMs = 500)

        val output = adapter.call("{}")

        assertTrue(output.startsWith("Error:"), "Exception should return error string, got: $output")
        assertTrue(output.contains("failing_tool"), "Error should mention tool name, got: $output")
        // R271: exception 클래스명이 LLM 출력에서 제거됨 (보안/사용자 노출 방지).
        // 클래스명은 logger.error(e)로 ops 로그에만 기록.
        assertFalse(
            output.contains("IllegalStateException"),
            "R271 fix: exception class name should NOT leak to LLM output, got: $output"
        )
        assertFalse(
            output.contains("disk full"),
            "R271 fix: exception message should NOT leak to LLM output, got: $output"
        )
    }

    // ========================================================================
    // R246: recordExecutionError 자동 기록 테스트
    // ========================================================================

    @Test
    fun `R246 일반 예외 발생 시 TOOL_CALL stage로 execution error가 기록되어야 한다`() {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)
        val callback = object : ToolCallback {
            override val name: String = "failing_tool"
            override val description: String = "tool that throws"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                throw IllegalStateException("connection refused")
            }
        }
        val adapter = ArcToolCallbackAdapter(
            arcCallback = callback,
            fallbackToolTimeoutMs = 500,
            evaluationCollector = collector
        )

        adapter.call("{}")

        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "tool_call")
            .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalStateException")
            .counter()
        assertNotNull(counter) {
            "tool_call + IllegalStateException Counter가 등록되어야 한다"
        }
        assertEquals(1.0, counter!!.count()) { "1회 기록" }
    }

    @Test
    fun `R246 TimeoutCancellationException도 TOOL_CALL stage로 기록되어야 한다`() {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)
        val callback = object : ToolCallback {
            override val name: String = "slow_tool"
            override val description: String = "slow tool"
            override val timeoutMs: Long = 30
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                delay(200)
                return "late"
            }
        }
        val adapter = ArcToolCallbackAdapter(
            arcCallback = callback,
            fallbackToolTimeoutMs = 500,
            evaluationCollector = collector
        )

        adapter.call("{}")

        // 타임아웃도 execution.error로 기록되어야 함 — exception은 TimeoutCancellationException
        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "tool_call")
            .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "TimeoutCancellationException")
            .counter()
        assertNotNull(counter) {
            "타임아웃도 TOOL_CALL stage로 기록되어야 한다"
        }
        assertEquals(1.0, counter!!.count())
    }

    @Test
    fun `R246 정상 실행에서는 execution error가 기록되지 않아야 한다`() {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)
        val callback = object : ToolCallback {
            override val name: String = "echo"
            override val description: String = "echo tool"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                return "ok"
            }
        }
        val adapter = ArcToolCallbackAdapter(
            arcCallback = callback,
            fallbackToolTimeoutMs = 500,
            evaluationCollector = collector
        )

        val output = adapter.call("""{"message":"hi"}""")

        assertEquals("ok", output)
        // 정상 경로는 execution.error 메트릭 없음
        val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .counter()
        assertNull(meter) {
            "정상 실행에서는 execution.error Counter가 등록되지 않아야 한다"
        }
    }

    @Test
    fun `R246 collector 미주입 시 기본 NoOp으로 동작하고 예외 없어야 한다 (backward compat)`() {
        val callback = object : ToolCallback {
            override val name: String = "failing_tool"
            override val description: String = "tool that throws"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                throw RuntimeException("boom")
            }
        }
        // evaluationCollector 파라미터 생략 → NoOpEvaluationMetricsCollector 기본값
        val adapter = ArcToolCallbackAdapter(
            arcCallback = callback,
            fallbackToolTimeoutMs = 500
        )

        val output = adapter.call("{}")

        assertTrue(output.startsWith("Error:")) {
            "기본값 NoOp에서도 예외 처리 경로는 그대로 동작"
        }
        // R271: exception 클래스명 LLM 노출 제거 — tool name만 포함되어야 함
        assertTrue(output.contains("failing_tool")) {
            "에러 메시지에 tool name 포함"
        }
        assertFalse(
            output.contains("RuntimeException"),
            "R271 fix: exception 클래스명이 LLM 출력에 노출되면 안 됨"
        )
    }

    @Test
    fun `R246 여러 도구 예외가 각각 기록되어야 한다`() {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)

        // 두 개의 다른 도구가 서로 다른 예외 throw
        val toolA = object : ToolCallback {
            override val name: String = "tool_a"
            override val description: String = "a"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                throw IllegalArgumentException("invalid input")
            }
        }
        val toolB = object : ToolCallback {
            override val name: String = "tool_b"
            override val description: String = "b"
            override suspend fun call(arguments: Map<String, Any?>): Any? {
                throw IllegalStateException("disk full")
            }
        }

        val adapterA = ArcToolCallbackAdapter(toolA, 500, collector)
        val adapterB = ArcToolCallbackAdapter(toolB, 500, collector)

        adapterA.call("{}")
        adapterA.call("{}")
        adapterB.call("{}")

        val counterA = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "tool_call")
            .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalArgumentException")
            .counter()
        assertNotNull(counterA) { "tool_a 예외 Counter 등록" }
        assertEquals(2.0, counterA!!.count()) { "tool_a 2회 기록" }

        val counterB = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "tool_call")
            .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalStateException")
            .counter()
        assertNotNull(counterB) { "tool_b 예외 Counter 등록" }
        assertEquals(1.0, counterB!!.count()) { "tool_b 1회 기록" }
    }
}
