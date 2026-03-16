package com.arc.reactor.tracing.impl

import com.arc.reactor.tracing.ArcReactorTracer
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

/**
 * OpenTelemetry 기반 [ArcReactorTracer] 구현체.
 *
 * OTel API가 클래스패스에 있고 트레이싱이 활성화된 경우에만 사용된다.
 * [startSpan] 호출 시마다 현재 OTel 컨텍스트의 자식 스팬을 생성한다.
 *
 * WHY: OTel은 compileOnly 의존성이므로 런타임에 존재할 때만 이 구현체가 활성화된다.
 * `@ConditionalOnClass`로 자동 설정하여 OTel이 있으면 분산 트레이싱이 자동 활성화되고,
 * 없으면 NoOpArcReactorTracer가 사용된다.
 *
 * @param tracer [io.opentelemetry.api.OpenTelemetry] 인스턴스에서 얻은 OTel [Tracer]
 * @see ArcReactorTracer 트레이싱 추상화 인터페이스
 * @see com.arc.reactor.tracing.NoOpArcReactorTracer OTel 미사용 시 대안
 */
class OtelArcReactorTracer(private val tracer: Tracer) : ArcReactorTracer {

    /**
     * 현재 컨텍스트를 부모로 하여 새 스팬을 생성하고 시작한다.
     * 전달된 속성은 스팬 생성 시 즉시 설정된다.
     */
    override fun startSpan(name: String, attributes: Map<String, String>): ArcReactorTracer.SpanHandle {
        val builder = tracer.spanBuilder(name).setParent(Context.current())
        for ((key, value) in attributes) {
            builder.setAttribute(key, value)
        }
        val span = builder.startSpan()
        return OtelSpanHandle(span)
    }

    /**
     * OTel [Span]을 래핑하는 스팬 핸들.
     *
     * WHY: ArcReactorTracer.SpanHandle 인터페이스를 통해 OTel 구체 타입을
     * 외부에 노출하지 않는다. 이를 통해 코어 코드가 OTel에 직접 의존하지 않게 한다.
     */
    private class OtelSpanHandle(private val span: Span) : ArcReactorTracer.SpanHandle {

        /** 스팬에 에러 상태와 예외 이벤트를 기록한다. */
        override fun setError(e: Throwable) {
            span.setStatus(StatusCode.ERROR, e.message.orEmpty())
            span.recordException(e)
        }

        /** 스팬에 속성을 추가한다. */
        override fun setAttribute(key: String, value: String) {
            span.setAttribute(key, value)
        }

        /** 스팬을 종료한다. */
        override fun close() {
            span.end()
        }
    }
}
