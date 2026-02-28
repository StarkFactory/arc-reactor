package com.arc.reactor.tracing.impl

import com.arc.reactor.tracing.ArcReactorTracer
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

/**
 * OpenTelemetry-backed implementation of [ArcReactorTracer].
 *
 * Active only when the OTel API is on the classpath and tracing is enabled.
 * Each [startSpan] call creates a child span of the current OTel context.
 *
 * @param tracer OTel [Tracer] obtained from an [io.opentelemetry.api.OpenTelemetry] instance
 */
class OtelArcReactorTracer(private val tracer: Tracer) : ArcReactorTracer {

    override fun startSpan(name: String, attributes: Map<String, String>): ArcReactorTracer.SpanHandle {
        val builder = tracer.spanBuilder(name).setParent(Context.current())
        for ((key, value) in attributes) {
            builder.setAttribute(key, value)
        }
        val span = builder.startSpan()
        return OtelSpanHandle(span)
    }

    private class OtelSpanHandle(private val span: Span) : ArcReactorTracer.SpanHandle {

        override fun setError(e: Throwable) {
            span.setStatus(StatusCode.ERROR, e.message.orEmpty())
            span.recordException(e)
        }

        override fun setAttribute(key: String, value: String) {
            span.setAttribute(key, value)
        }

        override fun close() {
            span.end()
        }
    }
}
