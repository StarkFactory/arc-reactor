package com.arc.reactor.admin.tracing

import com.arc.reactor.admin.collection.TenantResolver
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor

/**
 * Injects tenant_id into every span automatically.
 */
class TenantSpanProcessor(
    private val tenantResolver: TenantResolver
) : SpanProcessor {

    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        span.setAttribute(TENANT_ID_KEY, tenantResolver.currentTenantId())
    }

    override fun onEnd(span: ReadableSpan) {
        // no-op
    }

    override fun isStartRequired(): Boolean = true
    override fun isEndRequired(): Boolean = false

    companion object {
        private val TENANT_ID_KEY = AttributeKey.stringKey("tenant_id")
    }
}
