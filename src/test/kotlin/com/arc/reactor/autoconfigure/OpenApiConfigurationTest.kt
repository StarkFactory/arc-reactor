package com.arc.reactor.autoconfigure

import io.swagger.v3.oas.models.security.SecurityScheme
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for OpenAPI configuration bean.
 */
class OpenApiConfigurationTest {

    private val config = OpenApiConfiguration()

    @Nested
    inner class OpenAPIBeanConfiguration {

        @Test
        fun `should have correct title and version`() {
            val openAPI = config.arcReactorOpenAPI()

            assertEquals("Arc Reactor API", openAPI.info.title,
                "OpenAPI title should be 'Arc Reactor API'")
            assertEquals("2.1.0", openAPI.info.version,
                "OpenAPI version should match project version")
        }

        @Test
        fun `should have Apache 2 license`() {
            val openAPI = config.arcReactorOpenAPI()

            assertNotNull(openAPI.info.license, "License should be set")
            assertEquals("Apache 2.0", openAPI.info.license.name,
                "License should be Apache 2.0")
        }

        @Test
        fun `should configure Bearer JWT security scheme`() {
            val openAPI = config.arcReactorOpenAPI()

            val scheme = openAPI.components.securitySchemes["bearerAuth"]
            assertNotNull(scheme, "bearerAuth security scheme should exist")
            assertEquals(SecurityScheme.Type.HTTP, scheme!!.type,
                "Security scheme type should be HTTP")
            assertEquals("bearer", scheme.scheme,
                "Security scheme should be 'bearer'")
            assertEquals("JWT", scheme.bearerFormat,
                "Bearer format should be 'JWT'")
        }

        @Test
        fun `should have global security requirement`() {
            val openAPI = config.arcReactorOpenAPI()

            assertFalse(openAPI.security.isNullOrEmpty(),
                "Global security requirements should be set")
            assertTrue(openAPI.security.any { it.containsKey("bearerAuth") },
                "Should reference bearerAuth in security requirements")
        }

        @Test
        fun `should have description mentioning auth is conditional`() {
            val openAPI = config.arcReactorOpenAPI()

            assertTrue(openAPI.info.description.contains("arc.reactor.auth.enabled"),
                "Description should mention that auth is conditional")
        }
    }
}
