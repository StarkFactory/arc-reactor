package com.arc.reactor.admin.tracing

import com.arc.reactor.admin.collection.TenantResolver
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor

/**
 * 모든 span에 tenant_id를 자동으로 주입하는 SpanProcessor.
 *
 * OTel [Context]에서 우선 조회하고 (스레드 전환에도 안정적),
 * [TenantResolver]의 ThreadLocal로 폴백한다 (하위 호환성).
 */
class TenantSpanProcessor(
    private val tenantResolver: TenantResolver
) : SpanProcessor {

    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        val tenantId = parentContext.get(TENANT_CONTEXT_KEY)
            ?: tenantResolver.currentTenantId()
        span.setAttribute(TENANT_ID_KEY, tenantId)
    }

    override fun onEnd(span: ReadableSpan) {
        // no-op
    }

    override fun isStartRequired(): Boolean = true
    override fun isEndRequired(): Boolean = false

    companion object {
        private val TENANT_ID_KEY = AttributeKey.stringKey("tenant_id")
        val TENANT_CONTEXT_KEY: ContextKey<String> = ContextKey.named("arc.tenant_id")
    }
}
