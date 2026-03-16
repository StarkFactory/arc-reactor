package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.result.method.RequestMappingInfo
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.method.HandlerMethod

/**
 * AdminCapabilitiesController에 대한 테스트.
 *
 * 관리자 기능 목록 REST API의 동작을 검증합니다.
 */
class AdminCapabilitiesControllerTest {

    private fun exchange(role: UserRole): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>(relaxed = true)
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to role
        )
        return exchange
    }

    @Test
    fun `capability은(는) manifest rejects non-admin callers`() {
        val handlerMapping = mockk<RequestMappingHandlerMapping>()
        val controller = AdminCapabilitiesController(handlerMapping)

        val response = controller.getCapabilities(exchange(UserRole.USER))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Non-admin callers must receive 403" }
    }

    @Test
    fun `capability은(는) manifest returns api routes only`() {
        val handlerMapping = mockk<RequestMappingHandlerMapping>()
        every { handlerMapping.handlerMethods } returns linkedMapOf(
            RequestMappingInfo.paths("/api/ops/dashboard").build() to mockk<HandlerMethod>(),
            RequestMappingInfo.paths("/api/admin/platform/health").build() to mockk<HandlerMethod>(),
            RequestMappingInfo.paths("/actuator/health").build() to mockk<HandlerMethod>(),
            RequestMappingInfo.paths("/api/mcp/servers/{name}/preflight").build() to mockk<HandlerMethod>()
        )
        val controller = AdminCapabilitiesController(handlerMapping)

        val response = controller.getCapabilities(exchange(UserRole.ADMIN_MANAGER))

        assertEquals(HttpStatus.OK, response.statusCode) { "Any admin should receive 200 OK" }
        val body = response.body as AdminCapabilitiesResponse
        assertEquals("request-mappings", body.source) { "Manifest should declare its data source" }
        assertTrue(body.paths.contains("/api/ops/dashboard")) {
            "Manifest should include regular API routes"
        }
        assertTrue(body.paths.contains("/api/admin/platform/health")) {
            "Manifest should include admin API routes"
        }
        assertTrue(body.paths.contains("/api/mcp/servers/{name}/preflight")) {
            "Manifest should preserve templated API paths"
        }
        assertFalse(body.paths.contains("/actuator/health")) {
            "Manifest should exclude non-/api routes to avoid leaking irrelevant endpoints"
        }
    }
}
