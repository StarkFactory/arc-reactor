package com.arc.reactor.admin.collection

import com.arc.reactor.admin.tracing.TenantSpanProcessor
import io.opentelemetry.context.Context
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Resolves the current tenant ID.
 *
 * Preferred: [resolveTenantId] with [ServerWebExchange] — reliable in WebFlux (no ThreadLocal).
 * Legacy: [currentTenantId] via ThreadLocal — only safe when thread-hop is impossible.
 *
 * The companion [TenantWebFilter] stores the resolved tenant both in
 * exchange attributes (WebFlux-safe) and ThreadLocal (legacy compatibility).
 */
class TenantResolver {

    /**
     * Resolves tenant ID from exchange attributes/headers. WebFlux-safe — no ThreadLocal.
     */
    fun resolveTenantId(exchange: ServerWebExchange): String {
        val attr = exchange.attributes[EXCHANGE_ATTR_KEY] as? String
        if (!attr.isNullOrBlank()) return attr
        val header = exchange.request.headers.getFirst(HEADER_NAME)
        if (!header.isNullOrBlank()) return header
        return "default"
    }

    /**
     * ThreadLocal-based tenant ID. **Unreliable in WebFlux reactive chains** — use
     * [resolveTenantId] with exchange instead for controller endpoints.
     */
    fun currentTenantId(): String = TENANT_ID.get() ?: "default"

    fun setTenantId(tenantId: String) {
        TENANT_ID.set(tenantId)
    }

    fun clear() {
        TENANT_ID.remove()
    }

    companion object {
        private val TENANT_ID = ThreadLocal<String?>()
        const val HEADER_NAME = "X-Tenant-Id"
        const val EXCHANGE_ATTR_KEY = "resolvedTenantId"
    }
}

/**
 * WebFilter that extracts tenant ID from the request (header or attribute)
 * and sets it on the TenantResolver. Clears after the request completes
 * to prevent ThreadLocal leaks in pooled thread environments.
 *
 * Runs at highest priority to ensure tenant is available to all downstream filters.
 */
class TenantWebFilter(
    private val tenantResolver: TenantResolver
) : WebFilter, Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 10

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val tenantId = extractTenantId(exchange)
        tenantResolver.setTenantId(tenantId)
        exchange.attributes[TenantResolver.EXCHANGE_ATTR_KEY] = tenantId

        // Also propagate via OTel Context for TenantSpanProcessor
        val otelScope = Context.current()
            .with(TenantSpanProcessor.TENANT_CONTEXT_KEY, tenantId)
            .makeCurrent()

        return chain.filter(exchange)
            .doFinally {
                otelScope.close()
                tenantResolver.clear()
            }
    }

    private fun extractTenantId(exchange: ServerWebExchange): String {
        // 1. Check request header
        val header = exchange.request.headers.getFirst(TenantResolver.HEADER_NAME)
        if (!header.isNullOrBlank()) return header

        // 2. Check legacy exchange attribute key used by older filters/adapters.
        val attr = exchange.attributes["tenantId"] as? String
        if (!attr.isNullOrBlank()) return attr

        return "default"
    }
}
