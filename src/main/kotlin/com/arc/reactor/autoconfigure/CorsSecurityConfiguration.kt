package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

/**
 * CORS Configuration for Arc Reactor API.
 *
 * Opt-in: set `arc.reactor.cors.enabled=true` to activate.
 *
 * Configurable via `arc.reactor.cors.*` properties:
 * - `allowed-origins` : Allowed origins (default: `http://localhost:3000`)
 * - `allowed-methods` : Allowed HTTP methods (default: GET, POST, PUT, DELETE, OPTIONS)
 * - `allowed-headers` : Allowed headers (default: `*`)
 * - `allow-credentials` : Allow credentials (default: true)
 * - `max-age` : Preflight cache duration in seconds (default: 3600)
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.cors", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class CorsSecurityConfiguration {

    @Bean
    fun corsWebFilter(properties: AgentProperties): CorsWebFilter {
        val corsProps = properties.cors
        val config = CorsConfiguration().apply {
            allowedOrigins = corsProps.allowedOrigins
            allowedMethods = corsProps.allowedMethods
            allowedHeaders = corsProps.allowedHeaders
            allowCredentials = corsProps.allowCredentials
            maxAge = corsProps.maxAge
        }

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", config)
        return CorsWebFilter(source)
    }
}
