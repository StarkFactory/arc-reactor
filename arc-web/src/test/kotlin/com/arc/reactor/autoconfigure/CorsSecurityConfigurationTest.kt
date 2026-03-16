package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.CorsProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * CORS 보안 설정에 대한 테스트.
 *
 * CORS 설정의 올바른 적용을 검증합니다.
 */
class CorsSecurityConfigurationTest {

    private val configuration = CorsSecurityConfiguration()

    @Nested
    inner class DefaultProperties {

        @Test
        fun `default properties로 create CorsWebFilter해야 한다`() {
            val properties = AgentProperties(cors = CorsProperties())
            val filter = configuration.corsWebFilter(properties)

            assertNotNull(filter) { "CorsWebFilter should be created" }
        }
    }

    @Nested
    inner class CustomProperties {

        @Test
        fun `accept custom allowed origins해야 한다`() {
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
        fun `have sensible defaults해야 한다`() {
            val defaults = CorsProperties()

            assertFalse(defaults.enabled) { "CORS should be opt-in (disabled by default)" }
            assertEquals(listOf("http://localhost:3000"), defaults.allowedOrigins) {
                "Default origin should be localhost:3000"
            }
            assertTrue(defaults.allowedMethods.contains("GET")) { "GET should be allowed" }
            assertTrue(defaults.allowedMethods.contains("POST")) { "POST should be allowed" }
            assertTrue(defaults.allowedMethods.contains("OPTIONS")) { "OPTIONS should be allowed for preflight" }
            assertFalse(defaults.allowCredentials) { "Credentials should be denied by default (opt-in for security)" }
            assertEquals(3600, defaults.maxAge) { "Default max-age should be 3600 seconds" }
        }
    }
}
