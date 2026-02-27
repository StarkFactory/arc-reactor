package com.arc.reactor.controller

import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException

internal object TenantContextResolver {
    private const val TENANT_HEADER_NAME = "X-Tenant-Id"
    private const val RESOLVED_TENANT_ID_ATTRIBUTE = "resolvedTenantId"
    private const val LEGACY_TENANT_ID_ATTRIBUTE = "tenantId"
    private const val DEFAULT_TENANT_ID = "default"

    fun resolveTenantId(exchange: ServerWebExchange, authEnabled: Boolean): String {
        val resolvedTenantId = exchange.attributes[RESOLVED_TENANT_ID_ATTRIBUTE]?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
        val legacyTenantId = exchange.attributes[LEGACY_TENANT_ID_ATTRIBUTE]?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
        val tenantHeader = exchange.request.headers.getFirst(TENANT_HEADER_NAME)?.trim()
            ?.takeIf { it.isNotBlank() }

        val serverTenantId = resolvedTenantId ?: legacyTenantId
        if (serverTenantId != null && tenantHeader != null && serverTenantId != tenantHeader) {
            throw ServerWebInputException("Tenant header does not match resolved tenant context")
        }

        val effectiveTenantId = serverTenantId ?: tenantHeader
        if (effectiveTenantId != null) return effectiveTenantId
        if (authEnabled) {
            throw ServerWebInputException("Missing tenant context")
        }
        return DEFAULT_TENANT_ID
    }
}
