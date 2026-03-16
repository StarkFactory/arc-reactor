package com.arc.reactor.autoconfigure

import io.swagger.v3.oas.models.security.SecurityScheme
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.info.BuildProperties
import java.util.Properties

/**
 * 에 대한 테스트. OpenAPI configuration bean.
 */
class OpenApiConfigurationTest {

    private val config = OpenApiConfiguration()

    @Nested
    inner class OpenAPIBeanConfiguration {

        @Test
        fun `have correct title and version해야 한다`() {
            val openAPI = config.arcReactorOpenAPI()

            assertEquals("Arc Reactor API", openAPI.info.title,
                "OpenAPI title should be 'Arc Reactor API'")
            assertEquals("dev", openAPI.info.version,
                "OpenAPI version should default to 'dev' when build info is unavailable")
        }

        @Test
        fun `have Apache 2 license해야 한다`() {
            val openAPI = config.arcReactorOpenAPI()

            assertNotNull(openAPI.info.license, "License should be set")
            assertEquals("Apache 2.0", openAPI.info.license.name,
                "License should be Apache 2.0")
        }

        @Test
        fun `configure Bearer JWT security scheme해야 한다`() {
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
        fun `have global security requirement해야 한다`() {
            val openAPI = config.arcReactorOpenAPI()

            assertFalse(openAPI.security.isNullOrEmpty(),
                "Global security requirements should be set")
            assertTrue(openAPI.security.any { it.containsKey("bearerAuth") },
                "Should reference bearerAuth in security requirements")
        }

        @Test
        fun `have description mentioning auth and api version contract해야 한다`() {
            val openAPI = config.arcReactorOpenAPI()

            assertTrue(openAPI.info.description.contains("Authentication is mandatory"),
                "Description should mention that auth is mandatory")
            assertTrue(openAPI.info.description.contains("X-Arc-Api-Version"),
                "Description should mention API version contract header")
        }

        @Test
        fun `available일 때 prefer build properties version해야 한다`() {
            val properties = Properties().apply { setProperty("version", "9.9.9-test") }
            val openAPI = OpenApiConfiguration(BuildProperties(properties)).arcReactorOpenAPI()

            assertEquals("9.9.9-test", openAPI.info.version,
                "OpenAPI version should use BuildProperties when present")
        }
    }
}
