package com.arc.reactor.autoconfigure

import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.config.ChatModelProvider
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment

private val logger = KotlinLogging.logger {}

/**
 * 애플리케이션이 준비되면 시작 요약을 로깅한다.
 * 설정된 프로바이더, 활성화된 기능, 유용한 URL을 보여준다.
 */
class StartupInfoLogger(
    private val environment: Environment,
    private val chatModelProvider: ChatModelProvider,
    private val authProperties: AuthProperties
) {

    @EventListener(ApplicationReadyEvent::class)
    fun logStartupInfo() {
        val port = environment.getProperty("server.port", "8080")
        val provider = chatModelProvider.defaultProvider()
        val defaultTenantId = authProperties.defaultTenantId
        val publicActuatorHealth = environment.getProperty(
            "arc.reactor.auth.public-actuator-health",
            "true"
        )
        val postgresRequired = environment.getProperty("arc.reactor.postgres.required", "true")
        val datasourceConfigured = !environment.getProperty("spring.datasource.url").isNullOrBlank()
        val ragEnabled = environment.getProperty("arc.reactor.rag.enabled", "false")
        val guardEnabled = environment.getProperty("arc.reactor.guard.enabled", "true")

        logger.info {
            """
            |
            |===== Arc Reactor Started =====
            |  API:      http://localhost:$port/api/chat
            |  Swagger:  http://localhost:$port/swagger-ui.html
            |  Health:   http://localhost:$port/actuator/health
            |  Provider: $provider
            |  Auth:     enabled (required)
            |  Tenant:   $defaultTenantId
            |  Probe:    public-actuator-health=$publicActuatorHealth
            |  DB:       postgres-required=$postgresRequired, datasource-configured=$datasourceConfigured
            |  RAG:      ${if (ragEnabled == "true") "enabled" else "disabled"}
            |  Guard:    ${if (guardEnabled == "true") "enabled" else "disabled"}
            |================================
            """.trimMargin()
        }
    }
}
