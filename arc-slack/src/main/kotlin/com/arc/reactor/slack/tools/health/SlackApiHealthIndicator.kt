package com.arc.reactor.slack.tools.health

import com.arc.reactor.slack.tools.config.ConversationScopeMode
import com.arc.reactor.slack.tools.config.SlackToolsProperties
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.auth.AuthTestRequest
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

class SlackApiHealthIndicator(
    private val methodsClient: MethodsClient,
    private val properties: SlackToolsProperties
) : HealthIndicator {

    override fun health(): Health {
        return try {
            val response = methodsClient.authTest(AuthTestRequest.builder().build())
            if (!response.isOk) {
                return degradedHealth()
                    .withDetail("error", response.error ?: "auth_test_failed")
                    .withDetail("needed", response.needed ?: "")
                    .withDetail("provided", response.provided ?: "")
                    .build()
            }

            val grantedScopes = parseScopes(response.httpResponseHeaders)
            if (grantedScopes.isEmpty()) {
                return degradedHealth()
                    .withDetail("error", "scope_header_missing")
                    .withDetail("message", "x-oauth-scopes header is missing from Slack auth.test response.")
                    .build()
            }

            val requiredScopes = requiredAllScopes(properties)
            val missingScopes = requiredScopes.filterNot { it in grantedScopes }
            if (missingScopes.isNotEmpty()) {
                return degradedHealth()
                    .withDetail("error", "missing_scopes")
                    .withDetail("missingScopes", missingScopes.sorted())
                    .withDetail("grantedScopes", grantedScopes.sorted())
                    .build()
            }

            val missingAnyGroups = requiredAnyScopeGroups(properties)
                .mapNotNull { group ->
                    if (group.any { it in grantedScopes }) null else group.sorted()
                }
            if (missingAnyGroups.isNotEmpty()) {
                return degradedHealth()
                    .withDetail("error", "missing_any_scope_group")
                    .withDetail("missingAnyScopeGroups", missingAnyGroups)
                    .withDetail("grantedScopes", grantedScopes.sorted())
                    .build()
            }

            Health.up()
                .withDetail("teamId", response.teamId ?: "")
                .withDetail("botId", response.botId ?: "")
                .withDetail("scopeCount", grantedScopes.size)
                .build()
        } catch (e: Exception) {
            degradedHealth()
                .withException(e)
                .withDetail("error", "auth_test_exception")
                .build()
        }
    }

    private fun parseScopes(headers: Map<String, List<String>>?): Set<String> {
        if (headers == null) return emptySet()
        val raw = headers.entries
            .firstOrNull { (key, _) -> key.equals("x-oauth-scopes", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (raw.isBlank()) return emptySet()

        return raw.split(",")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private companion object {
        private fun degradedHealth(): Health.Builder {
            // Slack tools are optional for serving the main app. Surface integration drift
            // without forcing the whole /actuator/health response to DOWN.
            return Health.unknown().withDetail("optionalIntegration", "slack_tools")
        }

        private val BASE_REQUIRED_SCOPES = setOf(
            "chat:write",
            "users:read",
            "users:read.email",
            "reactions:write",
            "files:write"
        )

        private fun requiredAllScopes(properties: SlackToolsProperties): Set<String> {
            val scopes = BASE_REQUIRED_SCOPES.toMutableSet()
            if (properties.canvas.enabled) {
                scopes.add("canvases:write")
            }
            if (properties.toolExposure.conversationScopeMode == ConversationScopeMode.PUBLIC_ONLY) {
                scopes.add("channels:read")
                scopes.add("channels:history")
            }
            return scopes
        }

        private fun requiredAnyScopeGroups(properties: SlackToolsProperties): List<Set<String>> {
            return when (properties.toolExposure.conversationScopeMode) {
                ConversationScopeMode.PUBLIC_ONLY -> emptyList()
                ConversationScopeMode.INCLUDE_PRIVATE_AND_DM -> listOf(
                    setOf("channels:read", "groups:read", "im:read", "mpim:read"),
                    setOf("channels:history", "groups:history", "im:history", "mpim:history")
                )
            }
        }
    }
}
