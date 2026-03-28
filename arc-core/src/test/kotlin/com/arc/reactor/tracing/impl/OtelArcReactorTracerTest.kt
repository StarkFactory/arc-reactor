package com.arc.reactor.tracing.impl

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OtelArcReactorTracer")
class OtelArcReactorTracerTest {

    private lateinit var otelTracer: Tracer
    private lateinit var spanBuilder: SpanBuilder
    private lateinit var span: Span
    private lateinit var arcTracer: OtelArcReactorTracer

    @BeforeEach
    fun setUp() {
        span = mockk(relaxed = true)
        spanBuilder = mockk()
        otelTracer = mockk()

        every { otelTracer.spanBuilder(any()) } returns spanBuilder
        every { spanBuilder.setParent(any()) } returns spanBuilder
        every { spanBuilder.setAttribute(any<String>(), any<String>()) } returns spanBuilder
        every { spanBuilder.startSpan() } returns span

        arcTracer = OtelArcReactorTracer(otelTracer)
    }

    // ─── startSpan ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("startSpan — 스팬 생성")
    inner class StartSpan {

        @Test
        fun `스팬 이름을 OTel SpanBuilder에 전달한다`() {
            arcTracer.startSpan("arc.agent.request")

            verify { otelTracer.spanBuilder("arc.agent.request") }
        }

        @Test
        fun `현재 OTel 컨텍스트를 부모로 설정한다`() {
            arcTracer.startSpan("arc.agent.request")

            verify { spanBuilder.setParent(Context.current()) }
        }

        @Test
        fun `속성 없이 호출하면 spanBuilder의 setAttribute는 호출되지 않는다`() {
            arcTracer.startSpan("arc.agent.request")

            verify(exactly = 0) { spanBuilder.setAttribute(any<String>(), any<String>()) }
        }

        @Test
        fun `속성 맵의 모든 키-값 쌍을 SpanBuilder에 설정한다`() {
            val attrs = mapOf("agent.id" to "a1", "session.id" to "s1")

            arcTracer.startSpan("arc.agent.request", attrs)

            verify { spanBuilder.setAttribute("agent.id", "a1") }
            verify { spanBuilder.setAttribute("session.id", "s1") }
        }

        @Test
        fun `startSpan을 호출하면 반드시 OTel 스팬이 시작된다`() {
            arcTracer.startSpan("arc.agent.request")

            verify { spanBuilder.startSpan() }
        }

        @Test
        fun `반환된 핸들은 null이 아니다`() {
            val handle = arcTracer.startSpan("arc.agent.request")

            assert(handle != null) { "startSpan은 null이 아닌 SpanHandle을 반환해야 한다" }
        }
    }

    // ─── SpanHandle.setAttribute ───────────────────────────────────────────────

    @Nested
    @DisplayName("SpanHandle.setAttribute — 스팬 속성 추가")
    inner class SetAttribute {

        @Test
        fun `setAttribute는 OTel Span에 키-값 속성을 기록한다`() {
            val handle = arcTracer.startSpan("arc.agent.request")

            handle.setAttribute("arc.result", "success")

            verify { span.setAttribute("arc.result", "success") }
        }

        @Test
        fun `여러 속성을 연속으로 설정할 수 있다`() {
            val handle = arcTracer.startSpan("arc.agent.request")

            handle.setAttribute("k1", "v1")
            handle.setAttribute("k2", "v2")

            verify { span.setAttribute("k1", "v1") }
            verify { span.setAttribute("k2", "v2") }
        }

        @Test
        fun `빈 문자열 속성도 예외 없이 기록한다`() {
            val handle = arcTracer.startSpan("arc.agent.request")

            assertDoesNotThrow(
                { handle.setAttribute("", "") },
                "빈 문자열 속성도 예외 없이 처리되어야 한다"
            )
        }
    }

    // ─── SpanHandle.setError ───────────────────────────────────────────────────

    @Nested
    @DisplayName("SpanHandle.setError — 에러 기록")
    inner class SetError {

        @Test
        fun `setError는 OTel 스팬 상태를 ERROR로 설정한다`() {
            val handle = arcTracer.startSpan("arc.agent.request")
            val ex = RuntimeException("처리 실패")

            handle.setError(ex)

            verify { span.setStatus(StatusCode.ERROR, "처리 실패") }
        }

        @Test
        fun `setError는 OTel 스팬에 예외 이벤트를 기록한다`() {
            val handle = arcTracer.startSpan("arc.agent.request")
            val ex = IllegalStateException("상태 오류")

            handle.setError(ex)

            verify { span.recordException(ex) }
        }

        @Test
        fun `예외 메시지가 null이면 빈 문자열로 상태를 설정한다`() {
            val handle = arcTracer.startSpan("arc.agent.request")
            val ex = RuntimeException(null as String?)

            handle.setError(ex)

            verify { span.setStatus(StatusCode.ERROR, "") }
        }

        @Test
        fun `setError 후 close를 호출해도 예외가 발생하지 않는다`() {
            val handle = arcTracer.startSpan("arc.agent.request")
            val ex = RuntimeException("오류")

            assertDoesNotThrow({
                handle.setError(ex)
                handle.close()
            }, "setError → close 순서로 호출해도 예외가 없어야 한다")
        }
    }

    // ─── SpanHandle.close ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("SpanHandle.close — 스팬 종료")
    inner class Close {

        @Test
        fun `close는 OTel 스팬을 종료한다`() {
            val handle = arcTracer.startSpan("arc.agent.request")

            handle.close()

            verify { span.end() }
        }

        @Test
        fun `use 블록으로 스팬을 자동 종료할 수 있다`() {
            arcTracer.startSpan("arc.agent.request").use { handle ->
                handle.setAttribute("arc.phase", "test")
            }

            verify { span.end() }
        }
    }

    // ─── 전체 사용 흐름 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("전체 사용 흐름")
    inner class FullUsageFlow {

        @Test
        fun `정상 흐름 - 속성 설정 후 스팬을 종료한다`() {
            val handle = arcTracer.startSpan(
                "arc.tool.call",
                mapOf("tool.name" to "searchTool")
            )
            handle.setAttribute("tool.result", "ok")
            handle.close()

            verify { spanBuilder.setAttribute("tool.name", "searchTool") }
            verify { span.setAttribute("tool.result", "ok") }
            verify { span.end() }
        }

        @Test
        fun `에러 흐름 - setError 후 스팬을 종료한다`() {
            val handle = arcTracer.startSpan("arc.tool.call")
            val ex = RuntimeException("도구 호출 실패")
            handle.setError(ex)
            handle.close()

            verify { span.setStatus(StatusCode.ERROR, "도구 호출 실패") }
            verify { span.recordException(ex) }
            verify { span.end() }
        }

        @Test
        fun `복수 스팬을 독립적으로 생성하면 각각 별도의 OTel 스팬이 시작된다`() {
            val span2: Span = mockk(relaxed = true)
            val spanBuilder2: SpanBuilder = mockk()
            every { otelTracer.spanBuilder("arc.guard.input") } returns spanBuilder2
            every { spanBuilder2.setParent(any()) } returns spanBuilder2
            every { spanBuilder2.startSpan() } returns span2

            arcTracer.startSpan("arc.agent.request").close()
            arcTracer.startSpan("arc.guard.input").close()

            verify { span.end() }
            verify { span2.end() }
        }
    }
}
