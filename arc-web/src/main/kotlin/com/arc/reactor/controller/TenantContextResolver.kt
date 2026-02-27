package com.arc.reactor.controller

import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException

private val TENANT_ID_PATTERN = Regex("^[a-zA-Z0-9_-]{1,64}$")
private const val TENANT_ID_INVALID_MSG =
    "Invalid tenant ID format. Only alphanumeric characters, hyphens, and underscores are allowed (max 64 chars)"

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
            ?.also { validateTenantIdFormat(it) }

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

    private fun validateTenantIdFormat(tenantId: String) {
        if (!TENANT_ID_PATTERN.matches(tenantId)) {
            throw ServerWebInputException(TENANT_ID_INVALID_MSG)
        }
    }
}
