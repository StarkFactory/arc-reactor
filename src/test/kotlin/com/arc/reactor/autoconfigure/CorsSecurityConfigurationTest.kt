package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.CorsProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod

class CorsSecurityConfigurationTest {

    private val configuration = CorsSecurityConfiguration()

    @Nested
    inner class DefaultProperties {

        @Test
        fun `should create CorsWebFilter with default properties`() {
            val properties = AgentProperties(cors = CorsProperties())
            val filter = configuration.corsWebFilter(properties)

            assertNotNull(filter) { "CorsWebFilter should be created" }
        }
    }

    @Nested
    inner class CustomProperties {

        @Test
        fun `should accept custom allowed origins`() {
            val corsProps = CorsProperties(
                allowedOrigins = listOf("https://example.com", "https://app.example.com"),
                allowedMethods = listOf("GET", "POST"),
                allowCredentials = false,
                maxAge = 7200
            )
            val properties = AgentProperties(cors = corsProps)
            val filter = configuration.corsWebFilter(properties)

            assertNotNull(filter) { "CorsWebFilter should be created with custom origins" }
        }
    }

    @Nested
    inner class CorsPropertiesDefaults {

        @Test
        fun `should have sensible defaults`() {
            val defaults = CorsProperties()

            assertFalse(defaults.enabled) { "CORS should be opt-in (disabled by default)" }
            assertEquals(listOf("http://localhost:3000"), defaults.allowedOrigins) {
                "Default origin should be localhost:3000"
            }
            assertTrue(defaults.allowedMethods.contains("GET")) { "GET should be allowed" }
            assertTrue(defaults.allowedMethods.contains("POST")) { "POST should be allowed" }
            assertTrue(defaults.allowedMethods.contains("OPTIONS")) { "OPTIONS should be allowed for preflight" }
            assertTrue(defaults.allowCredentials) { "Credentials should be allowed by default" }
            assertEquals(3600, defaults.maxAge) { "Default max-age should be 3600 seconds" }
        }
    }
}
