package com.arc.reactor.controller

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

    private fun exchangeWithTenantHeader(tenantId: String): ServerWebExchange {
        val headers = HttpHeaders()
        headers.add("X-Tenant-Id", tenantId)
        return mockExchange(headers = headers)
    }

    @Test
    fun `rejects request when tenant context is missing`() {
        val exception = try {
            TenantContextResolver.resolveTenantId(mockExchange())
            throw AssertionError("Missing tenant context should be rejected")
        } catch (e: ServerWebInputException) {
            e
        }

        assertTrue(exception.reason?.contains("Missing tenant context") == true) {
            "Tenant resolution should fail-close without tenant context"
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

        val tenantId = TenantContextResolver.resolveTenantId(exchange)

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
            TenantContextResolver.resolveTenantId(exchange)
            throw AssertionError("Mismatched tenant header should be rejected")
        } catch (e: ServerWebInputException) {
            e
        }

        assertTrue(exception.reason?.contains("Tenant header does not match resolved tenant context") == true) {
            "Tenant spoofing mismatch should be blocked"
        }
    }

    @Nested
    inner class TenantIdFormatValidation {

        @ParameterizedTest(name = "valid tenant id: \"{0}\"")
        @ValueSource(strings = ["abc", "ABC", "tenant-1", "tenant_1", "A1b2C3", "a", "a-b_c-1"])
        fun `accepts valid tenant id formats`(tenantId: String) {
            val result = TenantContextResolver.resolveTenantId(exchangeWithTenantHeader(tenantId))

            assertEquals(tenantId, result) {
                "Valid tenant ID '$tenantId' should be accepted and returned as-is"
            }
        }

        @Test
        fun `accepts tenant id of exactly 64 characters`() {
            val tenantId = "a".repeat(64)
            val result = TenantContextResolver.resolveTenantId(exchangeWithTenantHeader(tenantId))

            assertEquals(tenantId, result) {
                "Tenant ID of exactly 64 characters should be accepted"
            }
        }

        @Test
        @Tag("regression")
        fun `should reject X-Tenant-Id header exceeding 64 characters`() {
            val tenantId = "a".repeat(65)

            val exception = try {
                TenantContextResolver.resolveTenantId(exchangeWithTenantHeader(tenantId))
                throw AssertionError("Tenant ID exceeding 64 chars should be rejected")
            } catch (e: ServerWebInputException) {
                e
            }

            assertTrue(exception.reason?.contains("Invalid tenant ID format") == true) {
                "Tenant ID of 65 characters should be rejected with format error"
            }
        }

        @Test
        fun `treats blank tenant header as missing tenant and rejects request`() {
            val exception = try {
                TenantContextResolver.resolveTenantId(exchangeWithTenantHeader("   "))
                throw AssertionError("Blank tenant header should be treated as missing and rejected")
            } catch (e: ServerWebInputException) {
                e
            }

            assertTrue(exception.reason?.contains("Missing tenant context") == true) {
                "Blank tenant header should fail-close as missing tenant context"
            }
        }

        @Tag("regression")
        @ParameterizedTest(name = "should reject X-Tenant-Id header containing injection or invalid characters: \"{0}\"")
        @ValueSource(strings = [
            "tenant id",
            "tenant.name",
            "tenant@domain",
            "tenant/path",
            "tenant\\path",
            "<script>",
            "'; DROP TABLE tenants; --",
            "tenant\u0000null",
            "tenant\ninjection",
            "tenant\rinjection",
            "tenant!",
            "tenant#",
            "tenant%20"
        ])
        fun `should reject X-Tenant-Id header containing injection attempt or invalid characters`(tenantId: String) {
            val exception = try {
                TenantContextResolver.resolveTenantId(exchangeWithTenantHeader(tenantId))
                throw AssertionError("Invalid tenant ID '$tenantId' should have been rejected")
            } catch (e: ServerWebInputException) {
                e
            }

            assertTrue(exception.reason?.contains("Invalid tenant ID format") == true) {
                "Tenant ID '$tenantId' should be rejected with format validation error"
            }
        }
    }
}
