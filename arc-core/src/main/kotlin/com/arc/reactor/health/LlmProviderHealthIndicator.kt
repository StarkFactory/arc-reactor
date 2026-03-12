package com.arc.reactor.health

import mu.KotlinLogging
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.core.env.Environment

private val logger = KotlinLogging.logger {}

/**
 * Health indicator that verifies at least one LLM provider is configured.
 *
 * Reports UP when at least one provider has a valid API key configured,
 * DOWN when no provider is configured at all.
 * Does NOT make actual API calls (health checks run frequently; API calls cost money).
 *
 * Provider details are included so operators can see which providers are available:
 * ```json
 * {
 *   "status": "UP",
 *   "details": {
 *     "gemini": "configured",
 *     "openai": "not configured",
 *     "anthropic": "not configured",
 *     "defaultProvider": "gemini",
 *     "configuredCount": 1
 *   }
 * }
 * ```
 */
class LlmProviderHealthIndicator(
    private val environment: Environment
) : HealthIndicator {

    override fun health(): Health {
        val providerStatuses = PROVIDER_ENV_KEYS.map { (name, envKey) ->
            val configured = isProviderConfigured(envKey)
            name to configured
        }

        val details = linkedMapOf<String, Any>()
        for ((name, configured) in providerStatuses) {
            details[name] = if (configured) "configured" else "not configured"
        }

        val configuredCount = providerStatuses.count { (_, configured) -> configured }
        val defaultProvider = environment.getProperty(
            "arc.reactor.llm.default-provider",
            "gemini"
        )
        details["defaultProvider"] = defaultProvider
        details["configuredCount"] = configuredCount

        return if (configuredCount > 0) {
            logger.debug { "LLM health: UP ($configuredCount provider(s) configured)" }
            Health.up().withDetails(details).build()
        } else {
            logger.warn { "LLM health: DOWN (no providers configured)" }
            Health.down()
                .withDetails(details)
                .withDetail("reason", "No LLM provider API key is configured")
                .build()
        }
    }

    private fun isProviderConfigured(envKey: String): Boolean {
        val value = environment.getProperty(envKey)?.trim().orEmpty()
        return value.isNotBlank()
    }

    private companion object {
        /**
         * Mapping of provider name to the environment variable / property
         * that holds its API key. Spring Boot automatically binds
         * `GEMINI_API_KEY` to `gemini.api.key` (relaxed binding), but the
         * actual env var names are the canonical source.
         */
        val PROVIDER_ENV_KEYS = listOf(
            "gemini" to "gemini.api.key",
            "openai" to "spring.ai.openai.api-key",
            "anthropic" to "spring.ai.anthropic.api-key"
        )
    }
}
