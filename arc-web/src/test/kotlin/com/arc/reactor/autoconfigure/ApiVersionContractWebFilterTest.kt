package com.arc.reactor.autoconfigure

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean

class ApiVersionContractWebFilterTest {

    private val filter = ApiVersionContractWebFilter(
        objectMapper = ObjectMapper(),
        currentVersion = "v1",
        supportedVersions = setOf("v1")
    )

    private fun executeFilter(requestedVersion: String? = null): Pair<MockServerWebExchange, AtomicBoolean> {
        val requestBuilder = MockServerHttpRequest.get("/api/test")
        if (requestedVersion != null) {
            requestBuilder.header(ApiVersionContractWebFilter.API_VERSION_HEADER, requestedVersion)
        }
        val exchange = MockServerWebExchange.from(requestBuilder.build())
        val chainCalled = AtomicBoolean(false)
        val chain = WebFilterChain {
            chainCalled.set(true)
            Mono.empty()
        }
        filter.filter(exchange, chain).block()
        return exchange to chainCalled
    }

    @Nested
    inner class ContractHeaders {

        @Test
        fun `should include current and supported version headers`() {
            val (exchange, _) = executeFilter()

            assertEquals(
                "v1",
                exchange.response.headers.getFirst(ApiVersionContractWebFilter.API_VERSION_HEADER)
            ) { "Response should include current API version header" }
            assertEquals(
                "v1",
                exchange.response.headers.getFirst(ApiVersionContractWebFilter.API_SUPPORTED_VERSIONS_HEADER)
            ) { "Response should include supported API versions header" }
        }
    }

    @Nested
    inner class VersionValidation {

        @Test
        fun `should allow requests when version header is missing`() {
            val (_, chainCalled) = executeFilter()

            assertTrue(chainCalled.get()) { "Request without version header should pass" }
        }

        @Test
        fun `should allow requests with supported version`() {
            val (_, chainCalled) = executeFilter("v1")

            assertTrue(chainCalled.get()) { "Request with supported version should pass" }
        }

        @Test
        fun `should reject requests with unsupported version`() {
            val (exchange, chainCalled) = executeFilter("v9")

            assertFalse(chainCalled.get()) { "Unsupported version should not proceed to downstream chain" }
            assertEquals(HttpStatus.BAD_REQUEST, exchange.response.statusCode) {
                "Unsupported version should return 400 BAD_REQUEST"
            }
            assertEquals(MediaType.APPLICATION_JSON, exchange.response.headers.contentType) {
                "Unsupported version response should be JSON"
            }
            assertTrue(
                exchange.response.bodyAsString.block().orEmpty().contains("Unsupported API version 'v9'")
            ) { "Error body should describe unsupported API version" }
        }
    }

    @Nested
    inner class Ordering {

        @Test
        fun `should run after security headers filter`() {
            assertEquals(Ordered.HIGHEST_PRECEDENCE + 2, filter.order) {
                "API version filter should run after auth/security header filters"
            }
        }
    }
}
