package com.arc.reactor.autoconfigure

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.web.server.WebFilter

/**
 * Arc Reactor Web Auto Configuration
 *
 * Configures HTTP/REST layer beans: security headers, CORS, OpenAPI.
 * Separated from core auto-configuration to support modular gateway architecture.
 */
@AutoConfiguration
class ArcReactorWebAutoConfiguration {

    /**
     * Security Headers WebFilter (default: enabled)
     */
    @Bean
    @ConditionalOnMissingBean(name = ["securityHeadersWebFilter"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.security-headers", name = ["enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun securityHeadersWebFilter(): WebFilter = SecurityHeadersWebFilter()

    /**
     * API version contract WebFilter (default: enabled)
     */
    @Bean
    @ConditionalOnMissingBean(name = ["apiVersionContractWebFilter"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.api-version", name = ["enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun apiVersionContractWebFilter(
        objectMapper: ObjectMapper,
        environment: Environment
    ): WebFilter {
        val currentVersion = environment.getProperty("arc.reactor.api-version.current", "v1")
        val supportedVersions = environment
            .getProperty("arc.reactor.api-version.supported", currentVersion)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .ifEmpty { setOf(currentVersion) }
        return ApiVersionContractWebFilter(
            objectMapper = objectMapper,
            currentVersion = currentVersion,
            supportedVersions = supportedVersions
        )
    }
}
