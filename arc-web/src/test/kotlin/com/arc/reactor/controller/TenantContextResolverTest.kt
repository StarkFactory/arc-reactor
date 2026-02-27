package com.arc.reactor.controller

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException

class TenantContextResolverTest {

    private fun mockExchange(
        attributes: MutableMap<String, Any> = mutableMapOf(),
        headers: HttpHeaders = HttpHeaders()
    ): ServerWebExchange {
        val request = mockk<ServerHttpRequest>()
        every { request.headers } returns headers
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns attributes
        every { exchange.request } returns request
        return exchange
    }

    @Test
    fun `returns default tenant when auth is disabled and tenant context is missing`() {
        val tenantId = TenantContextResolver.resolveTenantId(mockExchange(), authEnabled = false)

        assertEquals("default", tenantId) {
            "Auth-disabled mode should fallback to default tenant"
        }
    }

    @Test
    fun `rejects request when auth is enabled and tenant context is missing`() {
        val exception = try {
            TenantContextResolver.resolveTenantId(mockExchange(), authEnabled = true)
            throw AssertionError("Missing tenant context should be rejected")
        } catch (e: ServerWebInputException) {
            e
        }

        assertTrue(exception.reason?.contains("Missing tenant context") == true) {
            "Auth-enabled mode should fail-close without tenant context"
        }
    }

    @Test
    fun `uses resolved tenant attribute when present`() {
        val headers = HttpHeaders()
        headers.add("X-Tenant-Id", "tenant-a")
        val exchange = mockExchange(
            attributes = mutableMapOf("resolvedTenantId" to "tenant-a"),
            headers = headers
        )

        val tenantId = TenantContextResolver.resolveTenantId(exchange, authEnabled = true)

        assertEquals("tenant-a", tenantId) {
            "Resolved tenant attribute should be used as effective tenant"
        }
    }

    @Test
    fun `rejects request when tenant header mismatches resolved tenant attribute`() {
        val headers = HttpHeaders()
        headers.add("X-Tenant-Id", "tenant-b")
        val exchange = mockExchange(
            attributes = mutableMapOf("resolvedTenantId" to "tenant-a"),
            headers = headers
        )

        val exception = try {
            TenantContextResolver.resolveTenantId(exchange, authEnabled = true)
            throw AssertionError("Mismatched tenant header should be rejected")
        } catch (e: ServerWebInputException) {
            e
        }

        assertTrue(exception.reason?.contains("Tenant header does not match resolved tenant context") == true) {
            "Tenant spoofing mismatch should be blocked"
        }
    }
}
