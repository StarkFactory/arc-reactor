package com.arc.reactor.autoconfigure

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI / Swagger UI configuration for Arc Reactor API.
 *
 * Auto-configures when SpringDoc is on the classpath.
 * - Swagger UI: `/swagger-ui.html`
 * - OpenAPI spec: `/v3/api-docs`
 *
 * Disable in production via:
 * ```yaml
 * springdoc:
 *   api-docs.enabled: false
 *   swagger-ui.enabled: false
 * ```
 */
@Configuration
@ConditionalOnClass(name = ["org.springdoc.core.configuration.SpringDocConfiguration"])
class OpenApiConfiguration(
    private val buildProperties: BuildProperties? = null
) {

    @Bean
    fun arcReactorOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("Arc Reactor API")
                    .description(
                        "AI Agent framework REST API. " +
                            "Authentication endpoints are only available when `arc.reactor.auth.enabled=true`. " +
                            "API version contract: request `X-Arc-Api-Version` (default: v1)."
                    )
                    .version(resolveVersion())
                    .license(License().name("Apache 2.0").url("https://opensource.org/licenses/Apache-2.0"))
            )
            .components(
                Components()
                    .addSecuritySchemes(
                        "bearerAuth",
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .description(
                                "JWT token obtained from POST /api/auth/login. " +
                                    "Only required when arc.reactor.auth.enabled=true."
                            )
                    )
            )
            .addSecurityItem(SecurityRequirement().addList("bearerAuth"))
    }

    private fun resolveVersion(): String {
        return buildProperties?.version
            ?: OpenApiConfiguration::class.java.`package`?.implementationVersion
            ?: "dev"
    }
}
