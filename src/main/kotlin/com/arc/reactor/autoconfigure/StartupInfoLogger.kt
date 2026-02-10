package com.arc.reactor.autoconfigure

import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.config.ChatModelProvider
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment

private val logger = KotlinLogging.logger {}

/**
 * Logs a startup summary when the application is ready.
 * Shows configured provider, enabled features, and useful URLs.
 */
class StartupInfoLogger(
    private val environment: Environment,
    private val chatModelProvider: ChatModelProvider,
    private val authProperties: AuthProperties?
) {

    @EventListener(ApplicationReadyEvent::class)
    fun logStartupInfo() {
        val port = environment.getProperty("server.port", "8080")
        val provider = chatModelProvider.defaultProvider()
        val authEnabled = authProperties?.enabled ?: false
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
            |  Auth:     ${if (authEnabled) "enabled" else "disabled"}
            |  RAG:      ${if (ragEnabled == "true") "enabled" else "disabled"}
            |  Guard:    ${if (guardEnabled == "true") "enabled" else "disabled"}
            |================================
            """.trimMargin()
        }
    }
}
