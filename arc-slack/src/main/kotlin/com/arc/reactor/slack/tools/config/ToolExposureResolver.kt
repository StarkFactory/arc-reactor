package com.arc.reactor.slack.tools.config

import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.auth.AuthTestRequest
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

data class ToolCandidate(
    val name: String,
    val requiredScopes: Set<String>,
    val toolObject: Any
)

interface SlackScopeProvider {
    fun resolveGrantedScopes(): Set<String>
}

class SlackAuthTestScopeProvider(
    private val methodsClient: MethodsClient
) : SlackScopeProvider {

    override fun resolveGrantedScopes(): Set<String> {
        val response = methodsClient.authTest(AuthTestRequest.builder().build())
        if (!response.isOk) {
            throw IllegalStateException("Slack auth.test failed: ${response.error ?: "unknown_error"}")
        }

        val rawScopes = response.httpResponseHeaders
            ?.entries
            ?.firstOrNull { (key, _) -> key.equals("x-oauth-scopes", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

        if (rawScopes.isBlank()) {
            return emptySet()
        }

        return rawScopes.split(",")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }
}

class ToolExposureResolver(
    private val properties: SlackToolsProperties,
    private val slackScopeProvider: SlackScopeProvider
) {

    fun resolveToolObjects(candidates: List<ToolCandidate>): List<Any> {
        if (!properties.toolExposure.scopeAwareEnabled) {
            return candidates.map { it.toolObject }
        }

        val scopeResult = runCatching { slackScopeProvider.resolveGrantedScopes() }
        if (scopeResult.isFailure) {
            val ex = scopeResult.exceptionOrNull()
            if (properties.toolExposure.failOpenOnScopeResolutionError) {
                logger.warn(ex) {
                    "Scope-aware tool exposure failed to resolve scopes; fail-open enabled, exposing all tools."
                }
                return candidates.map { it.toolObject }
            }
            logger.error(ex) {
                "Scope-aware tool exposure failed to resolve scopes; fail-open disabled, exposing no tools."
            }
            return emptyList()
        }

        val grantedScopes = scopeResult.getOrNull().orEmpty()
        if (grantedScopes.isEmpty()) {
            if (properties.toolExposure.failOpenOnScopeResolutionError) {
                logger.warn {
                    "Scope-aware tool exposure resolved empty scope set; fail-open enabled, exposing all tools."
                }
                return candidates.map { it.toolObject }
            }
            logger.warn {
                "Scope-aware tool exposure resolved empty scope set; fail-open disabled, exposing no tools."
            }
            return emptyList()
        }

        val exposed = candidates.filter { candidate ->
            candidate.requiredScopes.all { it in grantedScopes }
        }
        val blocked = candidates.map { it.name }.toSet() - exposed.map { it.name }.toSet()
        if (blocked.isNotEmpty()) {
            logger.info {
                "Scope-aware tool exposure filtered tools by granted Slack scopes. " +
                    "exposed=${exposed.size}/${candidates.size}, blocked=${blocked.sorted()}"
            }
        } else {
            logger.info { "Scope-aware tool exposure enabled: all ${candidates.size} tools exposed." }
        }
        return exposed.map { it.toolObject }
    }
}
