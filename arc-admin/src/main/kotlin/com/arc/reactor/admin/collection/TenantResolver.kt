package com.arc.reactor.admin.collection

import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Resolves the current tenant ID from a ThreadLocal.
 *
 * The companion [TenantWebFilter] automatically sets and clears
 * the tenant ID for each HTTP request, preventing ThreadLocal leaks.
 *
 * For non-HTTP contexts (hooks, background jobs), call [setTenantId] / [clear] explicitly.
 */
class TenantResolver {

    fun currentTenantId(): String = TENANT_ID.get() ?: "default"

    fun setTenantId(tenantId: String) {
        TENANT_ID.set(tenantId)
    }

    fun clear() {
        TENANT_ID.remove()
    }

    companion object {
        private val TENANT_ID = ThreadLocal<String?>()
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

        return chain.filter(exchange)
            .doFinally { tenantResolver.clear() }
    }

    private fun extractTenantId(exchange: ServerWebExchange): String {
        // 1. Check request header
        val header = exchange.request.headers.getFirst("X-Tenant-Id")
        if (!header.isNullOrBlank()) return header

        // 2. Check exchange attribute (set by upstream auth filter)
        val attr = exchange.attributes["tenantId"] as? String
        if (!attr.isNullOrBlank()) return attr

        return "default"
    }
}
