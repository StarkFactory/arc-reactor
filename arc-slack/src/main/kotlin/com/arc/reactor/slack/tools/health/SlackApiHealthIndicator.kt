package com.arc.reactor.slack.tools.health

import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.auth.AuthTestRequest
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

class SlackApiHealthIndicator(
    private val methodsClient: MethodsClient
) : HealthIndicator {

    override fun health(): Health {
        return try {
            val response = methodsClient.authTest(AuthTestRequest.builder().build())
            if (!response.isOk) {
                return Health.down()
                    .withDetail("error", response.error ?: "auth_test_failed")
                    .withDetail("needed", response.needed ?: "")
                    .withDetail("provided", response.provided ?: "")
                    .build()
            }

            val grantedScopes = parseScopes(response.httpResponseHeaders)
            if (grantedScopes.isEmpty()) {
                return Health.down()
                    .withDetail("error", "scope_header_missing")
                    .withDetail("message", "x-oauth-scopes header is missing from Slack auth.test response.")
                    .build()
            }

            val missingScopes = REQUIRED_SCOPES.filterNot { it in grantedScopes }
            if (missingScopes.isNotEmpty()) {
                return Health.down()
                    .withDetail("error", "missing_scopes")
                    .withDetail("missingScopes", missingScopes.sorted())
                    .withDetail("grantedScopes", grantedScopes.sorted())
                    .build()
            }

            Health.up()
                .withDetail("teamId", response.teamId ?: "")
                .withDetail("botId", response.botId ?: "")
                .withDetail("scopeCount", grantedScopes.size)
                .build()
        } catch (e: Exception) {
            Health.down(e)
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
        private val REQUIRED_SCOPES = setOf(
            "chat:write",
            "channels:read",
            "channels:history",
            "users:read",
            "users:read.email",
            "reactions:write",
            "files:write",
            "search:read"
        )
    }
}
