package com.arc.reactor.tracing

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * ArcReactorTracer에 대한 테스트.
 *
 * 트레이싱 인터페이스의 기본 동작을 검증합니다.
 */
class ArcReactorTracerTest {

    @Nested
    inner class NoOpArcReactorTracerTests {

        private val tracer = NoOpArcReactorTracer()

        @Test
        fun `startSpan은(는) return a non-null handle해야 한다`() {
            val handle = tracer.startSpan("arc.agent.request")
            assertNotNull(handle, "Expected a non-null SpanHandle from NoOpArcReactorTracer")
        }

        @Test
        fun `startSpan with attributes은(는) return a non-null handle해야 한다`() {
            val handle = tracer.startSpan("arc.agent.request", mapOf("key" to "value"))
            assertNotNull(handle, "Expected a non-null SpanHandle even when attributes are provided")
        }

        @Test
        fun `close은(는) not throw해야 한다`() {
            val handle = tracer.startSpan("arc.agent.request")
            assertDoesNotThrow({ handle.close() }, "NoOp close should never throw")
        }

        @Test
        fun `setError은(는) not throw해야 한다`() {
            val handle = tracer.startSpan("arc.agent.request")
            assertDoesNotThrow(
                { handle.setError(RuntimeException("test error")) },
                "NoOp setError should never throw"
            )
        }

        @Test
        fun `setAttribute은(는) not throw해야 한다`() {
            val handle = tracer.startSpan("arc.agent.request")
            assertDoesNotThrow(
                { handle.setAttribute("key", "value") },
                "NoOp setAttribute should never throw"
            )
        }

        @Test
        fun `close called multiple times은(는) not throw해야 한다`() {
            val handle = tracer.startSpan("arc.agent.request")
            assertDoesNotThrow({
                handle.close()
                handle.close()
            }, "NoOp close should be idempotent")
        }

        @Test
        fun `span은(는) handle usable as AutoCloseable via use-block`() {
            assertDoesNotThrow({
                tracer.startSpan("arc.agent.guard").use { span ->
                    span.setAttribute("guard.result", "passed")
                }
            }, "NoOp SpanHandle should work inside a use-block")
        }
    }
}
