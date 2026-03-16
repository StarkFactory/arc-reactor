package com.arc.reactor.controller

import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException

private val TENANT_ID_PATTERN = Regex("^[a-zA-Z0-9_-]{1,64}$")
private const val TENANT_ID_INVALID_MSG =
    "Invalid tenant ID format. Only alphanumeric characters, hyphens, and underscores are allowed (max 64 chars)"

/**
 * 채팅 및 멀티파트 채팅 엔드포인트를 위한 테넌트 컨텍스트 리졸버.
 *
 * 해석 우선순위:
 * 1) `resolvedTenantId` exchange 속성 (JWT 필터에서 설정한 정식 테넌트)
 * 2) 레거시 `tenantId` exchange 속성 (호환성)
 * 3) `X-Tenant-Id` 요청 헤더 (형식 검증 포함)
 *
 * Fail-close 동작:
 * - 정식 컨텍스트와 헤더가 모두 존재하지만 불일치하면 400 반환
 * - 테넌트 컨텍스트를 확인할 수 없으면 400 반환
 */
internal object TenantContextResolver {
    private const val TENANT_HEADER_NAME = "X-Tenant-Id"
    private const val RESOLVED_TENANT_ID_ATTRIBUTE = "resolvedTenantId"
    private const val LEGACY_TENANT_ID_ATTRIBUTE = "tenantId"

    fun resolveTenantId(exchange: ServerWebExchange): String {
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
        throw ServerWebInputException("Missing tenant context")
    }

    private fun validateTenantIdFormat(tenantId: String) {
        if (!TENANT_ID_PATTERN.matches(tenantId)) {
            throw ServerWebInputException(TENANT_ID_INVALID_MSG)
        }
    }
}
