package com.arc.reactor.tracing

/**
 * Lightweight tracing abstraction for Arc Reactor.
 *
 * Provides span-based instrumentation without a hard dependency on OpenTelemetry.
 * When OTel is absent or tracing is disabled, [NoOpArcReactorTracer] is used — all
 * operations are no-ops with zero overhead.
 *
 * Implementations must be thread-safe.
 */
interface ArcReactorTracer {

    /**
     * Starts a new span with the given name and optional attributes.
     *
     * The returned [SpanHandle] must be closed (via [SpanHandle.close] or a use-block)
     * to end the span and release any associated resources.
     *
     * @param name span name, e.g. "arc.agent.request"
     * @param attributes initial key-value attributes attached to the span
     * @return an active [SpanHandle]
     */
    fun startSpan(name: String, attributes: Map<String, String> = emptyMap()): SpanHandle

    /**
     * Handle to an active span.
     *
     * Implementations of this interface must be safe to call even after [close].
     */
    interface SpanHandle : AutoCloseable {

        /** Records an error on the span. Should be called before [close]. */
        fun setError(e: Throwable)

        /** Adds or updates a single attribute on the active span. */
        fun setAttribute(key: String, value: String)

        /** Ends the span. Idempotent — safe to call multiple times. */
        override fun close()
    }
}

/**
 * No-op tracer used when OpenTelemetry is unavailable or tracing is disabled.
 *
 * All operations are empty and allocation-free (the same [NoOpSpanHandle] singleton
 * is returned for every [startSpan] call).
 */
class NoOpArcReactorTracer : ArcReactorTracer {

    override fun startSpan(name: String, attributes: Map<String, String>): ArcReactorTracer.SpanHandle =
        NoOpSpanHandle

    private object NoOpSpanHandle : ArcReactorTracer.SpanHandle {
        override fun setError(e: Throwable) = Unit
        override fun setAttribute(key: String, value: String) = Unit
        override fun close() = Unit
    }
}
