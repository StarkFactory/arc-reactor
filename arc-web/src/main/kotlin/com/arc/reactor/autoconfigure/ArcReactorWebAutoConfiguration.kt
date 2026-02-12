package com.arc.reactor.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
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
}
